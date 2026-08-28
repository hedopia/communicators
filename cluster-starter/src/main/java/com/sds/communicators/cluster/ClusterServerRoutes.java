package com.sds.communicators.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.cluster.support.RedirectFunction;
import com.sds.communicators.cluster.support.RouteDispatcher;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Slf4j
class ClusterServerRoutes {
    private final RedirectFunction redirectFunction;
    private final ClusterStarter clusterStarter;
    private final ClusterService clusterService;
    private final String clusterBasePath;
    private final HttpClient proxyClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String REDIRECT_TO_LEADER = "/redirect-to-leader";
    private static final String REDIRECT_TO_INDEX = "/redirect-to-index";

    /** hop-by-hop headers (RFC 9110 §7.6.1) plus Host, which is set from the target URI instead */
    private static final Set<String> NOT_FORWARDED = Set.of(
            "host", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    ClusterServerRoutes(RedirectFunction redirectFunction, ClusterStarter clusterStarter, ClusterService clusterService, String clusterBasePath,
                        int connectTimeoutMillis, int readTimeoutMillis) {
        this.redirectFunction = redirectFunction;
        this.clusterStarter = clusterStarter;
        this.clusterService = clusterService;
        this.clusterBasePath = clusterBasePath;
        this.proxyClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(Duration.ofMillis(readTimeoutMillis));
    }

    void apply(HttpServerRoutes routes) {
        redirect(routes);
        controller(routes);
        internal(routes);
    }

    /**
     * node-to-node communication, served on the same port as the public API.
     * These routes are required for the cluster to work: a node that mounts
     * {@link ClusterStarter#getRoutes()} on its own server must not filter them out.
     */
    private void internal(HttpServerRoutes routes) {
        String base = clusterBasePath + ClusterInternalClient.INTERNAL_PATH;

        routes.put(base + "/heartbeat", (request, response) -> {
            log.trace(request.uri());
            return body(request, ClusterInternalClient.HeartbeatRequest.class, response, heartbeat -> {
                if (clusterStarter.nodeIndex != heartbeat.nodeIndex)
                    clusterService.heartbeatReceived(heartbeat.nodeIndex, heartbeat.position, heartbeat.lastTransitionTime, heartbeat.sharedObjectSeq);
                return ok(response);
            });
        });
        routes.get(base + "/node-status", (request, response) -> {
            log.trace(request.uri());
            if (clusterStarter.isPrepared)
                return ok(response, new NodeStatus(clusterStarter.nodeIndex, clusterStarter.position, clusterStarter.isActivated));
            else
                return badRequest(response, "application is not prepared, get status ignored");
        });
        routes.put(base + "/set-to-leader", (request, response) -> {
            log.trace(request.uri());
            if (clusterStarter.isPrepared) {
                clusterService.transition(Position.LEADER);
                return ok(response);
            } else {
                return badRequest(response, "application is not prepared, set to leader ignored");
            }
        });
        routes.delete(base + "/cluster-deleted/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            if (clusterStarter.nodeIndex != nodeIndex)
                clusterService.clusterDeleted(nodeIndex);
            return ok(response);
        });
        routes.delete(base + "/remove-shared-object/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            if (clusterStarter.nodeIndex != nodeIndex)
                clusterService.removeSharedObject(nodeIndex);
            return ok(response);
        });
        routes.get(base + "/node-index", (request, response) -> {
            log.trace(request.uri());
            return ok(response, clusterStarter.nodeIndex);
        });
        routes.post(base + "/merge-shared-object-to-leader/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return body(request, ClusterService.MergeSharedObjectInfo.class, response, info -> {
                var ret = clusterService.setSharedObjectToLeader(nodeIndex, info);
                return ret == null ? ok(response) : badRequest(response, ret);
            });
        });
        routes.post(base + "/delete-shared-object-to-leader/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return body(request, ClusterService.DeleteSharedObjectInfo.class, response, info -> {
                var ret = clusterService.setSharedObjectToLeader(nodeIndex, info);
                return ret == null ? ok(response) : badRequest(response, ret);
            });
        });
        routes.post(base + "/check-merge-shared-object/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return body(request, ClusterService.MergeSharedObjectInfo.class, response, info ->
                    ok(response, clusterStarter.nodeIndex == nodeIndex || clusterService.checkSharedObject(nodeIndex, info)));
        });
        routes.post(base + "/check-delete-shared-object/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return body(request, ClusterService.DeleteSharedObjectInfo.class, response, info ->
                    ok(response, clusterStarter.nodeIndex == nodeIndex || clusterService.checkSharedObject(nodeIndex, info)));
        });
        routes.post(base + "/overwrite-shared-object/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return body(request, ClusterService.MergeSharedObjectInfo.class, response, info -> {
                if (clusterStarter.nodeIndex != nodeIndex)
                    clusterService.overwriteSharedObject(nodeIndex, info);
                return ok(response);
            });
        });
        routes.get(base + "/shared-object", (request, response) -> {
            log.trace(request.uri());
            return ok(response, new ClusterService.MergeSharedObjectInfo(
                    clusterService.sharedObjectSeq.get(clusterStarter.nodeIndex),
                    clusterService.sharedObject.get(clusterStarter.nodeIndex)));
        });
        routes.get(base + "/shared-object/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return ok(response, new ClusterService.MergeSharedObjectInfo(
                    clusterService.sharedObjectSeq.get(nodeIndex),
                    clusterService.sharedObject.get(nodeIndex)));
        });
        routes.post(base + "/sync-shared-object/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            return body(request, ClusterService.SharedObject.class, response, sharedObject -> {
                if (clusterStarter.nodeIndex != nodeIndex)
                    clusterService.syncSharedObject(sharedObject);
                return ok(response);
            });
        });
        routes.post(base + "/check-shared-object-seq", (request, response) -> {
            log.trace(request.uri());
            return body(request, new TypeReference<Map<Integer, Long>>() {}, response, sharedObjectSeq -> {
                var result = new HashSet<Integer>();
                for (var nodeIndex : sharedObjectSeq.keySet()) {
                    if (clusterStarter.nodeIndex != nodeIndex &&
                            (!clusterService.sharedObjectSeq.containsKey(nodeIndex) ||
                                    !clusterService.sharedObjectSeq.get(nodeIndex).equals(sharedObjectSeq.get(nodeIndex))))
                        result.add(nodeIndex);
                }
                return ok(response, result);
            });
        });
    }

    private <T> Mono<Void> body(HttpServerRequest request, Class<T> type, HttpServerResponse response, Function<T, Mono<Void>> handler) {
        return body(request, objectMapper.constructType(type), response, handler);
    }

    private <T> Mono<Void> body(HttpServerRequest request, TypeReference<T> type, HttpServerResponse response, Function<T, Mono<Void>> handler) {
        return body(request, objectMapper.getTypeFactory().constructType(type), response, handler);
    }

    private <T> Mono<Void> body(HttpServerRequest request, JavaType type, HttpServerResponse response, Function<T, Mono<Void>> handler) {
        return requestBody(request).flatMap(payload -> {
            T value;
            try {
                value = objectMapper.readValue(payload, type);
            } catch (JsonProcessingException e) {
                log.error("invalid internal request body ({})::{}", request.uri(), e.getMessage());
                return badRequest(response, "invalid request body::" + e.getMessage());
            }
            return handler.apply(value);
        });
    }

    private Mono<Void> ok(HttpServerResponse response) {
        return response.send();
    }

    private Mono<Void> ok(HttpServerResponse response, Object body) {
        return send(response, body);
    }

    private Mono<Void> badRequest(HttpServerResponse response, Object body) {
        return send(response.status(400), body);
    }

    private Mono<Void> send(HttpServerResponse response, Object body) {
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

    private Mono<String> requestBody(HttpServerRequest request) {
        // the body arrives on an event loop; continue the (blocking) handler on a worker thread
        return RouteDispatcher.continueOnWorker(
                request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty(""));
    }

    private void redirect(HttpServerRoutes routes) {
        routes.route(request -> request.fullPath().equals(REDIRECT_TO_LEADER) || request.fullPath().startsWith(REDIRECT_TO_LEADER + "/"),
                (request, response) -> {
                    log.trace(request.uri());
                    String pathAndQuery = request.uri().substring(REDIRECT_TO_LEADER.length());
                    String path = request.fullPath().substring(REDIRECT_TO_LEADER.length());
                    AtomicReference<String> targetUrl = new AtomicReference<>();
                    redirectFunction.toLeaderFuncConfirmed(targetUrl::set, path);
                    return proxy(request, response, targetUrl.get() + pathAndQuery);
                });
        routes.route(request -> request.fullPath().startsWith(REDIRECT_TO_INDEX + "/"),
                (request, response) -> {
                    log.trace(request.uri());
                    String remaining = request.fullPath().substring((REDIRECT_TO_INDEX + "/").length());
                    int slashIndex = remaining.indexOf('/');
                    String sNodeIndex = slashIndex < 0 ? remaining : remaining.substring(0, slashIndex);
                    int nodeIndex;
                    try {
                        nodeIndex = Integer.parseInt(sNodeIndex);
                    } catch (NumberFormatException e) {
                        return badRequest(response, "invalid node-index: " + sNodeIndex);
                    }
                    String pathAndQuery = request.uri().substring((REDIRECT_TO_INDEX + "/" + sNodeIndex).length());
                    String path = request.fullPath().substring((REDIRECT_TO_INDEX + "/" + sNodeIndex).length());
                    AtomicReference<String> targetUrl = new AtomicReference<>();
                    var ret = redirectFunction.toIndexFunc(nodeIndex, targetUrl::set, path);
                    if (ret == null)
                        return proxy(request, response, targetUrl.get() + pathAndQuery);
                    else
                        return badRequest(response, ret.getMessage());
                });
    }

    /** relays the request and streams the response through without buffering it */
    private Mono<Void> proxy(HttpServerRequest request, HttpServerResponse response, String uri) {
        return proxyClient
                .headers(headers -> {
                    for (var entry : request.requestHeaders()) {
                        if (!NOT_FORWARDED.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                            headers.add(entry.getKey(), entry.getValue());
                    }
                })
                .request(request.method())
                .uri(uri)
                .send(request.receive().retain())
                .response((clientResponse, body) -> {
                    response.status(clientResponse.status());
                    for (var entry : clientResponse.responseHeaders()) {
                        if (!NOT_FORWARDED.contains(entry.getKey().toLowerCase(Locale.ROOT)))
                            response.header(entry.getKey(), entry.getValue());
                    }
                    return response.send(body.retain());
                })
                .then();
    }

    private void controller(HttpServerRoutes routes) {
        routes.get(clusterBasePath + "/leader-url", (request, response) -> {
            log.trace(request.uri());
            AtomicReference<String> targetUrl = new AtomicReference<>();
            redirectFunction.toLeaderFuncConfirmed(targetUrl::set, "leader-url");
            return ok(response, targetUrl.get());
        });
        routes.get(clusterBasePath + "/index-url/{nodeIndex}", (request, response) -> {
            log.trace(request.uri());
            int nodeIndex = Integer.parseInt(request.param("nodeIndex"));
            AtomicReference<String> targetUrl = new AtomicReference<>();
            var ret = redirectFunction.toIndexFunc(nodeIndex, targetUrl::set, "index-url(" + nodeIndex + ")");
            if (ret == null)
                return ok(response, targetUrl.get());
            else
                return badRequest(response, ret.getMessage());
        });
        routes.get(clusterBasePath + "/node-status", (request, response) -> {
            log.trace(request.uri());
            if (clusterStarter.isPrepared) {
                NodeStatus status = new NodeStatus(clusterStarter.nodeIndex, clusterStarter.position, clusterStarter.isActivated);
                return ok(response, status);
            } else {
                return badRequest(response, "application is not prepared, get status ignored");
            }
        });
        routes.put(clusterBasePath + "/set-to-leader", (request, response) -> {
            log.trace(request.uri());
            if (clusterStarter.isPrepared) {
                clusterService.transition(Position.LEADER);
                return ok(response);
            } else {
                return badRequest(response, "application is not prepared, set to leader ignored");
            }
        });
        routes.put(clusterBasePath + "/set-to-follower", (request, response) -> {
            log.trace(request.uri());
            if (clusterStarter.isPrepared) {
                clusterService.transition(Position.FOLLOWER);
                return ok(response);
            } else {
                return badRequest(response, "application is not prepared, set to follower ignored");
            }
        });
        routes.get(clusterBasePath + "/shared-object-map", (request, response) -> {
            log.trace(request.uri());
            return ok(response, clusterService.sharedObject);
        });
        routes.get(clusterBasePath + "/shared-object-seq", (request, response) -> {
            log.trace(request.uri());
            return ok(response, clusterService.sharedObjectSeq);
        });
        routes.post(clusterBasePath + "/add-cluster-node", (request, response) -> {
            log.trace(request.uri());
            return requestBody(request).flatMap(url -> {
                if (clusterStarter.nodeTargetUrls.contains(url) || url.equals(clusterStarter.nodeUrl))
                    return badRequest(response, "node " + url + " already registered");
                else {
                    clusterStarter.nodeTargetUrls.add(url);
                    return ok(response);
                }
            });
        });
        routes.get(clusterBasePath + "/get-cluster-urls", (request, response) -> {
            log.trace(request.uri());
            var ret = new ArrayList<>(clusterStarter.nodeTargetUrls);
            ret.add(clusterStarter.nodeUrl);
            return ok(response, ret);
        });
        routes.get(clusterBasePath + "/get-cluster-nodes", (request, response) -> {
            log.trace(request.uri());
            return ok(response, clusterStarter.getCluster());
        });
        routes.get(clusterBasePath + "/get-node-index", (request, response) -> {
            log.trace(request.uri());
            return ok(response, clusterStarter.nodeIndex);
        });
    }
}
