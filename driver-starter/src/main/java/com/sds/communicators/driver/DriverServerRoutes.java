package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.cluster.RouteDispatcher;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.type.StatusCode;
import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
class DriverServerRoutes {
    static final String INTERNAL_PATH = "/internal";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static Consumer<HttpServerRoutes> getDriverServerRoutes(DriverStarter driverStarter, DriverService driverService, String driverBasePath, String clusterBasePath, Consumer<HttpServerRoutes> additionalRoutes) throws IOException {
        String template;
        try (InputStream in = DriverServerRoutes.class.getClassLoader().getResourceAsStream("static/index.html")) {
            if (in == null)
                throw new IOException("static/index.html not found in classpath");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var html = template.replace("__APP_BASE_PATH__", driverBasePath)
                .replace("__CLUSTER_BASE_PATH__", clusterBasePath);
        return routes -> {
            routes.get(driverBasePath + "/assets/{path}", (request, response) -> {
                log.trace(request.uri());
                String path = request.param("path");
                if (path == null || path.contains("..") || path.contains("/"))
                    return response.status(404).send();
                byte[] bytes;
                try (InputStream in = DriverServerRoutes.class.getClassLoader().getResourceAsStream("static/assets/" + path)) {
                    if (in == null)
                        return response.status(404).send();
                    bytes = in.readAllBytes();
                } catch (IOException e) {
                    return response.status(404).send();
                }
                return response.header(HttpHeaderNames.CONTENT_TYPE, contentType(path))
                        .sendByteArray(Mono.just(bytes))
                        .then();
            });
            // the UI html references assets relatively (vite base "./"), so it must be served
            // from the trailing-slash path only; canonicalize {driverBasePath} to {driverBasePath}/
            routes.get(driverBasePath, (request, response) -> {
                log.trace(request.uri());
                String uri = request.uri();
                int query = uri.indexOf('?');
                return response.status(301)
                        .header(HttpHeaderNames.LOCATION, driverBasePath + "/" + (query < 0 ? "" : uri.substring(query)))
                        .send();
            });
            routes.get(driverBasePath + "/", (request, response) -> {
                log.trace(request.uri());
                return response.header(HttpHeaderNames.CONTENT_TYPE, "text/html")
                        .sendString(Mono.just(html))
                        .then();
            });
            routes.post(driverBasePath + "/balanced-connect-all", (request, response) -> {
                log.trace(request.uri());
                return requestBody(request).flatMap(body -> {
                    try {
                        Set<Device> devices = objectMapper.readValue(body, new TypeReference<Set<Device>>() {});
                        return ok(response, driverService.balancedConnectAll(devices));
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.post(driverBasePath + "/connect-all", (request, response) -> {
                log.trace(request.uri());
                return requestBody(request).flatMap(body -> {
                    try {
                        Set<Device> devices = objectMapper.readValue(body, new TypeReference<Set<Device>>() {});
                        return ok(response, driverService.connectAllToLeader(driverService.clusterStarter.getNodeIndex(), devices));
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.delete(driverBasePath + "/disconnect-all", (request, response) -> {
                log.trace(request.uri());
                return ok(response, driverService.disconnectAll());
            });
            routes.delete(driverBasePath + "/disconnect", (request, response) -> {
                log.trace(request.uri());
                return requestBody(request).flatMap(body -> {
                    try {
                        List<String> deviceIds = objectMapper.readValue(body, new TypeReference<List<String>>() {});
                        return ok(response, driverService.disconnectList(deviceIds, false));
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.put(driverBasePath + "/reconnect-all", (request, response) -> {
                log.trace(request.uri());
                return ok(response, driverService.reconnectAll());
            });
            routes.get(driverBasePath + "/device-status/{deviceId}", (request, response) -> {
                log.trace(request.uri());
                String deviceId = request.param("deviceId");
                if (driverService.driverProtocols.containsKey(deviceId))
                    return ok(response, driverService.driverProtocols.get(deviceId).getStatus());
                else
                    return ok(response, StatusCode.DISCONNECTED);
            });
            routes.get(driverBasePath + "/device-status", (request, response) -> {
                log.trace(request.uri());
                var result = driverService.driverProtocols.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getStatus()));
                return ok(response, result);
            });
            routes.get(driverBasePath + "/device-id-map", (request, response) -> {
                log.trace(request.uri());
                return ok(response, driverStarter.getDeviceIdMap());
            });
            routes.get(driverBasePath + "/devices", (request, response) -> {
                log.trace(request.uri());
                // shared-object: deviceId -> device setting map (addDevices),
                // script data is merged under the device map's "data" key (DriverProtocol.setData),
                // so filter map values having "id"/"connectionUrl" keys to pick device entries only.
                // the maps are live HashMaps mutated concurrently by script setData / cluster merge,
                // so deep-copy each entry (with retry on ConcurrentModificationException) before responding
                Object result = null;
                Exception failure = null;
                for (int attempt = 0; attempt < 3 && result == null; attempt++) {
                    try {
                        var list = new ArrayList<>();
                        for (var nodeObjects : driverStarter.getClusterStarter().getSharedObjectMap().values()) {
                            for (var value : nodeObjects.values()) {
                                if (value instanceof Map<?, ?> map && map.containsKey("id") && map.containsKey("connectionUrl"))
                                    list.add(objectMapper.readValue(objectMapper.writeValueAsString(value), new TypeReference<Map<String, Object>>() {}));
                            }
                        }
                        result = list;
                    } catch (Exception e) {
                        failure = e;
                    }
                }
                if (result == null) {
                    log.error("get devices failed::{}", failure.getMessage());
                    return response.status(500)
                            .sendString(Mono.just("get devices failed::" + failure.getMessage()))
                            .then();
                }
                return ok(response, result);
            });
            routes.get(driverBasePath + "/response", (request, response) -> {
                log.trace(request.uri());
                return ok(response, driverService.responseMap);
            });
            routes.get(driverBasePath + "/response/{deviceId}", (request, response) -> {
                log.trace(request.uri());
                String deviceId = request.param("deviceId");
                return ok(response, driverService.responseMap.getOrDefault(deviceId, new HashMap<>()));
            });
            routes.post(driverBasePath + "/execute-commands/{deviceId}", (request, response) -> {
                log.trace(request.uri());
                String deviceId = request.param("deviceId");
                String initialValue = request.requestHeaders().get("initial-value");
                return requestBody(request).flatMap(body -> {
                    try {
                        Set<Command> commands = objectMapper.readValue(body, new TypeReference<Set<Command>>() {});
                        var ret = driverService.executeCommands(deviceId, commands, initialValue, true);
                        return executeCommandResponse(response, ret);
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.post(driverBasePath + "/request-commands/{deviceId}", (request, response) -> {
                log.trace(request.uri());
                String deviceId = request.param("deviceId");
                String initialValue = request.requestHeaders().get("initial-value");
                return requestBody(request).flatMap(body -> {
                    try {
                        Set<Command> commands = objectMapper.readValue(body, new TypeReference<Set<Command>>() {});
                        var ret = driverService.executeCommands(deviceId, commands, initialValue, false);
                        return executeCommandResponse(response, ret);
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.post(driverBasePath + "/execute-command-ids/{deviceId}", (request, response) -> {
                log.trace(request.uri());
                String deviceId = request.param("deviceId");
                String initialValue = request.requestHeaders().get("initial-value");
                return requestBody(request).flatMap(body -> {
                    try {
                        List<String> commandIdList = objectMapper.readValue(body, new TypeReference<List<String>>() {});
                        var ret = driverService.executeCommandIds(deviceId, commandIdList, initialValue, true);
                        return executeCommandResponse(response, ret);
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.post(driverBasePath + "/request-command-ids/{deviceId}", (request, response) -> {
                log.trace(request.uri());
                String deviceId = request.param("deviceId");
                String initialValue = request.requestHeaders().get("initial-value");
                return requestBody(request).flatMap(body -> {
                    try {
                        List<String> commandIdList = objectMapper.readValue(body, new TypeReference<List<String>>() {});
                        var ret = driverService.executeCommandIds(deviceId, commandIdList, initialValue, false);
                        return executeCommandResponse(response, ret);
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            // node-to-node communication, served on the same port as the public API
            routes.post(driverBasePath + INTERNAL_PATH + "/connect-all-to-index", (request, response) -> {
                log.trace(request.uri());
                return requestBody(request).flatMap(body -> {
                    try {
                        Set<Device> devices = objectMapper.readValue(body, new TypeReference<Set<Device>>() {});
                        return ok(response, driverService.connectAll(devices));
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });
            routes.post(driverBasePath + INTERNAL_PATH + "/connect-all-to-leader/{nodeIndex}", (request, response) -> {
                log.trace(request.uri());
                int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
                return requestBody(request).flatMap(body -> {
                    try {
                        Set<Device> devices = objectMapper.readValue(body, new TypeReference<Set<Device>>() {});
                        return ok(response, driverService.connectAllToLeader(nodeIndex, devices));
                    } catch (JsonProcessingException e) {
                        return badRequest(response, "invalid request body::" + e.getMessage());
                    }
                });
            });

            if (additionalRoutes != null)
                additionalRoutes.accept(routes);
        };
    }

    private static Mono<Void> executeCommandResponse(HttpServerResponse response, Object ret) {
        return ret == null ? ok(response) :
                (ret instanceof String ?
                        badRequest(response, ret) :
                        ok(response, ret));
    }

    private static Mono<Void> ok(HttpServerResponse response) {
        return response.send();
    }

    private static Mono<Void> ok(HttpServerResponse response, Object body) {
        return send(response, body);
    }

    private static Mono<Void> badRequest(HttpServerResponse response, Object body) {
        return send(response.status(400), body);
    }

    private static Mono<Void> send(HttpServerResponse response, Object body) {
        if (body instanceof String str)
            return response.header(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8")
                    .sendString(Mono.just(str))
                    .then();
        try {
            return response.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    .sendString(Mono.just(objectMapper.writeValueAsString(body)))
                    .then();
        } catch (JsonProcessingException e) {
            log.error("response body serialization failed::{}", e.getMessage());
            return response.status(500)
                    .sendString(Mono.just("response body serialization failed::" + e.getMessage()))
                    .then();
        }
    }

    private static Mono<String> requestBody(HttpServerRequest request) {
        // the body arrives on an event loop; continue the (blocking) handler on a virtual thread
        return RouteDispatcher.continueOnWorker(
                request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty(""));
    }

    private static String contentType(String path) {
        int idx = path.lastIndexOf('.');
        String ext = idx < 0 ? "" : path.substring(idx + 1).toLowerCase();
        return switch (ext) {
            case "css" -> "text/css";
            case "js" -> "application/javascript";
            case "html" -> "text/html";
            case "json", "map" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }
}
