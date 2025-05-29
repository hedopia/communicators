package com.sds.communicators.driver;

import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.type.StatusCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
class DriverServerRoutes {
    static RouterFunctions.Builder getDriverServerRoutes(DriverStarter driverStarter, DriverService driverService, String driverBasePath, RouterFunctions.Builder addRouterFunctionBuilder) {
        var routerFunctionBuilder = RouterFunctions.route().path(driverBasePath, builder ->
                builder
                        .POST("/balanced-connect-all", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return request.bodyToMono(new ParameterizedTypeReference<Set<Device>>() {})
                                    .flatMap(devices -> ServerResponse.ok()
                                            .bodyValue(driverService.balancedConnectAll(devices)));
                        })
                        .POST("/connect-all", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return request.bodyToMono(new ParameterizedTypeReference<Set<Device>>() {})
                                    .flatMap(devices ->
                                            ServerResponse.ok().bodyValue(driverService.connectAllToLeader(driverService.clusterStarter.getNodeIndex(), devices)));
                        })
                        .POST("/connect-all-to-index", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return request.bodyToMono(new ParameterizedTypeReference<Set<Device>>() {})
                                    .flatMap(devices -> ServerResponse.ok()
                                            .bodyValue(driverService.connectAll(devices)));
                        })
                        .POST("/connect-all-to-leader/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(new ParameterizedTypeReference<Set<Device>>() {})
                                    .flatMap(devices -> ServerResponse.ok().bodyValue(driverService.connectAllToLeader(nodeIndex, devices)));
                        })
                        .DELETE("/disconnect-all", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(driverService.disconnectAll());
                        })
                        .DELETE("/disconnect", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return request.bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                                    .flatMap(deviceIds ->
                                            ServerResponse.ok().bodyValue(driverService.disconnectList(deviceIds, false)));
                        })
                        .PUT("/reconnect-all", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(driverService.reconnectAll());
                        })
                        .GET("/device-status/{deviceId}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String deviceId = request.pathVariable("deviceId");
                            if (driverService.driverProtocols.containsKey(deviceId))
                                return ServerResponse.ok().bodyValue(driverService.driverProtocols.get(deviceId).getStatus());
                            else
                                return ServerResponse.ok().bodyValue(StatusCode.DISCONNECTED);
                        })
                        .GET("/device-status", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            var result = driverService.driverProtocols.entrySet().stream()
                                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getStatus()));
                            return ServerResponse.ok().bodyValue(result);
                        })
                        .GET("/device-id-map", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(driverStarter.getDeviceIdMap());
                        })
                        .GET("/response", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(driverService.responseMap);
                        })
                        .GET("/response/{deviceId}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String deviceId = request.pathVariable("deviceId");
                            return ServerResponse.ok().bodyValue(driverService.responseMap.getOrDefault(deviceId, new HashMap<>()));
                        })
                        .POST("/execute-commands/{deviceId}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String deviceId = request.pathVariable("deviceId");
                            String initialValue = request.queryParam("initial-value").isPresent() ? request.queryParam("initial-value").get() : null;
                            return request.bodyToMono(new ParameterizedTypeReference<Set<Command>>() {})
                                    .flatMap(commands -> {
                                        var ret = driverService.executeCommandsOnThread(deviceId, commands, initialValue, true);
                                        return executeCommandResponse(ret);
                                    });
                        })
                        .POST("/request-commands/{deviceId}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String deviceId = request.pathVariable("deviceId");
                            String initialValue = request.queryParam("initial-value").isPresent() ? request.queryParam("initial-value").get() : null;
                            return request.bodyToMono(new ParameterizedTypeReference<Set<Command>>() {})
                                    .flatMap(commands -> {
                                        var ret = driverService.executeCommandsOnThread(deviceId, commands, initialValue, false);
                                        return executeCommandResponse(ret);
                                    });
                        })
                        .POST("/execute-command-ids/{deviceId}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String deviceId = request.pathVariable("deviceId");
                            String initialValue = request.queryParam("initial-value").isPresent() ? request.queryParam("initial-value").get() : null;
                            return request.bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                                    .flatMap(commandIdList -> {
                                        var ret = driverService.executeCommandIdsOnThread(deviceId, commandIdList, initialValue, true);
                                        return executeCommandResponse(ret);
                                    });
                        })
                        .POST("/request-command-ids/{deviceId}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String deviceId = request.pathVariable("deviceId");
                            String initialValue = request.queryParam("initial-value").isPresent() ? request.queryParam("initial-value").get() : null;
                            return request.bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                                    .flatMap(commandIdList -> {
                                        var ret = driverService.executeCommandIdsOnThread(deviceId, commandIdList, initialValue, false);
                                        return executeCommandResponse(ret);
                                    });
                        })
        );
        if (addRouterFunctionBuilder != null)
            routerFunctionBuilder.add(addRouterFunctionBuilder.build());
        return routerFunctionBuilder;
    }

    private static Mono<ServerResponse> executeCommandResponse(Object ret) {
        return ret == null ? ServerResponse.ok().build() :
                (ret instanceof String ?
                        ServerResponse.badRequest().bodyValue(ret) :
                        ServerResponse.ok().bodyValue(ret));
    }
}
