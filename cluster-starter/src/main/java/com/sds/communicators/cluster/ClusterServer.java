package com.sds.communicators.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.http.codec.support.DefaultServerCodecConfigurer;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
class ClusterServer {
    private final int serverPort;
    private final RedirectFunction redirectFunction;
    private final ClusterStarter clusterStarter;
    private final ClusterService clusterService;
    private final String clusterBasePath;
    RouterFunctions.Builder routerFunctionBuilder;

    private static final String REDIRECT_TO_LEADER = "/redirect-to-leader";
    private static final String REDIRECT_TO_INDEX = "/redirect-to-index";

    ClusterServer(int serverPort, RedirectFunction redirectFunction, ClusterStarter clusterStarter, ClusterService clusterService, String clusterBasePath) {
        this.serverPort = serverPort;
        this.clusterStarter = clusterStarter;
        this.redirectFunction = redirectFunction;
        this.clusterService = clusterService;
        this.clusterBasePath = clusterBasePath;
        routerFunctionBuilder = redirect(RouterFunctions.route());
        routerFunctionBuilder = internal(routerFunctionBuilder, clusterBasePath);
        routerFunctionBuilder = controller(routerFunctionBuilder, clusterBasePath);
    }


    private DisposableServer server = null;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();

    void start(int serverThreadPoolSize, boolean httpServer) {
        if (server != null)
            server.disposeNow();

        if (httpServer) {
            var codecConfigurer = new DefaultServerCodecConfigurer();
            codecConfigurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
            codecConfigurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));

            var httpHandler = WebHttpHandlerBuilder
                    .webHandler(RouterFunctions.toWebHandler(routerFunctionBuilder.build()))
                    .codecConfigurer(codecConfigurer)
                    .build();

            server = HttpServer.create()
                    .port(serverPort)
                    .runOn(LoopResources.create("http", serverThreadPoolSize, true))
                    .doOnConnection(c -> {
                        channels.add(c.channel());
                        c.onDispose(() -> channels.remove(c.channel()));
                    })
                    .handle(new ReactorHttpHandlerAdapter(httpHandler))
                    .bindNow();
        }

        for (String targetUrl : clusterStarter.nodeTargetUrls) {
            try {
                var nodeIndex = clusterStarter.getClient(targetUrl, ClusterClient.ClusterClientApi.class).getNodeIndex(clusterBasePath);
                if (clusterStarter.nodeIndex == nodeIndex)
                    throw new Exception("this node (" + clusterStarter.nodeUrl + ") and (" + targetUrl + "), node-index(" + nodeIndex + ") duplicated");
            } catch (Exception ignored) {}
        }
        log.info("(node-index: {}, url: {}) started", clusterStarter.nodeIndex, clusterStarter.nodeUrl);
    }

    void dispose() {
        if (server != null) {
            server.disposeNow();
            for (Channel channel : channels) {
                try {
                    channel.close().get();
                } catch (Exception ignored) {}
            }
            server = null;
        }
    }

    void addRouterFunctionBuilder(RouterFunctions.Builder routerFunctionBuilder) {
        if (routerFunctionBuilder != null)
            this.routerFunctionBuilder = this.routerFunctionBuilder.add(routerFunctionBuilder.build());
    }

    private RouterFunctions.Builder redirect(RouterFunctions.Builder routerFunctionBuilder) {
        return routerFunctionBuilder.route(RequestPredicates.path(REDIRECT_TO_LEADER + "/**"),
                        request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String path = request.uri().getRawPath().substring(REDIRECT_TO_LEADER.length());
                            AtomicReference<String> targetUrl = new AtomicReference<>();
                            redirectFunction.toLeaderFuncConfirmed(targetUrl::set, path);
                            var method = request.method();
                            if (method != null)
                                return redirect(request, method, targetUrl.get() + path);
                            else
                                return ServerResponse.badRequest().bodyValue("invalid http method");
                        })
                .route(RequestPredicates.path(REDIRECT_TO_INDEX + "/{nodeIndex}/**"),
                        request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            String sNodeIndex = request.pathVariable("nodeIndex");
                            int nodeIndex = Integer.parseInt(sNodeIndex);
                            String path = request.uri().getRawPath().substring((REDIRECT_TO_INDEX + "/" + sNodeIndex).length());
                            AtomicReference<String> targetUrl = new AtomicReference<>();
                            var ret = redirectFunction.toIndexFunc(nodeIndex, targetUrl::set, path);
                            var method = request.method();
                            if (ret == null && method != null)
                                return redirect(request, method, targetUrl.get() + path);
                            else if (ret != null)
                                return ServerResponse.badRequest().bodyValue(ret.getMessage());
                            else
                                return ServerResponse.badRequest().bodyValue("invalid http method");
                        });
    }

    private Mono<ServerResponse> redirect(ServerRequest request, org.springframework.http.HttpMethod method, String path) {
        return HttpClient.create()
                .headers(headers -> request.headers().asHttpHeaders().forEach(headers::add))
                .request(HttpMethod.valueOf(method.name()))
                .uri(UriComponentsBuilder.fromHttpUrl(path).queryParams(request.queryParams()).toUriString())
                .send(ByteBufFlux.fromInbound(request.bodyToMono(byte[].class)))
                .responseSingle((response, byteBufMono) ->
                        byteBufMono.asByteArray().map(body ->
                                ServerResponse.status(response.status().code())
                                        .headers(httpHeaders -> response.responseHeaders())
                                        .body(BodyInserters.fromValue(body))
                        ))
                .flatMap(response -> response);
    }

    private RouterFunctions.Builder internal(RouterFunctions.Builder routerFunctionBuilder, String clusterBasePath) {
        return routerFunctionBuilder.path(clusterBasePath, builder ->
                builder
                        .PUT("/heartbeat/{nodeIndex}/{position}/{lastTransitionTime}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            long lastTransitionTime = Long.parseLong(request.pathVariable("lastTransitionTime"));
                            Position position = Position.valueOf(Position.class, request.pathVariable("position"));
                            return request.bodyToMono(new ParameterizedTypeReference<Map<Integer, Long>>() {})
                                    .flatMap(sharedObjectSeq -> {
                                                if (clusterStarter.nodeIndex != nodeIndex)
                                                    clusterService.heartbeatReceived(nodeIndex, position, lastTransitionTime, sharedObjectSeq);
                                                return ServerResponse.ok().build();
                                            }
                                    );
                        })
                        .DELETE("/cluster-deleted/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            if (clusterStarter.nodeIndex != nodeIndex)
                                clusterService.clusterDeleted(nodeIndex);
                            return ServerResponse.ok().build();
                        })
                        .DELETE("/remove-shared-object/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            if (clusterStarter.nodeIndex != nodeIndex)
                                clusterService.removeSharedObject(nodeIndex);
                            return ServerResponse.ok().build();
                        })
                        .GET("/get-shared-object", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            var sharedObjectInfo = new ClusterService.MergeSharedObjectInfo(clusterService.sharedObjectSeq.get(clusterStarter.nodeIndex),
                                    clusterService.sharedObject.get(clusterStarter.nodeIndex));
                            return ServerResponse.ok().bodyValue(sharedObjectInfo);
                        })
                        .GET("/get-shared-object/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            var sharedObjectInfo = new ClusterService.MergeSharedObjectInfo(clusterService.sharedObjectSeq.get(nodeIndex),
                                    clusterService.sharedObject.get(nodeIndex));
                            return ServerResponse.ok().bodyValue(sharedObjectInfo);
                        })
                        .POST("/merge-shared-object-to-leader/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(ClusterService.MergeSharedObjectInfo.class)
                                    .flatMap(sharedObjectInfo -> {
                                        var ret = clusterService.setSharedObjectToLeader(nodeIndex, sharedObjectInfo);
                                        return ret == null ? ServerResponse.ok().build() : ServerResponse.badRequest().bodyValue(ret);
                                    });
                        })
                        .POST("/delete-shared-object-to-leader/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(ClusterService.DeleteSharedObjectInfo.class)
                                    .flatMap(sharedObjectInfo -> {
                                        var ret = clusterService.setSharedObjectToLeader(nodeIndex, sharedObjectInfo);
                                        return ret == null ? ServerResponse.ok().build() : ServerResponse.badRequest().bodyValue(ret);
                                    });
                        })
                        .POST("/check-merge-shared-object/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(ClusterService.MergeSharedObjectInfo.class)
                                    .flatMap(sharedObjectInfo -> {
                                        if (clusterStarter.nodeIndex != nodeIndex)
                                            return ServerResponse.ok().bodyValue(clusterService.checkSharedObject(nodeIndex, sharedObjectInfo));
                                        else
                                            return ServerResponse.ok().bodyValue(true);
                                    });
                        })
                        .POST("/check-delete-shared-object/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(ClusterService.DeleteSharedObjectInfo.class)
                                    .flatMap(sharedObjectInfo -> {
                                        if (clusterStarter.nodeIndex != nodeIndex)
                                            return ServerResponse.ok().bodyValue(clusterService.checkSharedObject(nodeIndex, sharedObjectInfo));
                                        else
                                            return ServerResponse.ok().bodyValue(true);
                                    });
                        })
                        .POST("/overwrite-shared-object/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(ClusterService.MergeSharedObjectInfo.class)
                                    .flatMap(sharedObjectInfo -> {
                                        if (clusterStarter.nodeIndex != nodeIndex)
                                            clusterService.overwriteSharedObject(nodeIndex, sharedObjectInfo);
                                        return ServerResponse.ok().build();
                                    });
                        })
                        .POST("/sync-shared-object/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            return request.bodyToMono(ClusterService.SharedObject.class)
                                    .flatMap(sharedObject -> {
                                        if (clusterStarter.nodeIndex != nodeIndex)
                                            clusterService.syncSharedObject(sharedObject);
                                        return ServerResponse.ok().build();
                                    });
                        })
                        .POST("/check-shared-object-sequence", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return request.bodyToMono(new ParameterizedTypeReference<Map<Integer, Long>>() {})
                                    .flatMap(sharedObjectSeq -> {
                                        var result = new HashSet<Integer>();
                                        for (var nodeIndex : sharedObjectSeq.keySet()) {
                                            if (clusterStarter.nodeIndex != nodeIndex) {
                                                if (!clusterService.sharedObjectSeq.containsKey(nodeIndex) ||
                                                        !clusterService.sharedObjectSeq.get(nodeIndex).equals(sharedObjectSeq.get(nodeIndex)))
                                                    result.add(nodeIndex);
                                            }
                                        }
                                        return ServerResponse.ok().bodyValue(result);
                                    });
                        })
        );
    }

    private RouterFunctions.Builder controller(RouterFunctions.Builder routerFunctionBuilder, String clusterBasePath) {
        return routerFunctionBuilder.path(clusterBasePath, builder ->
                builder
                        .GET("/leader-url", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            AtomicReference<String> targetUrl = new AtomicReference<>();
                            redirectFunction.toLeaderFuncConfirmed(targetUrl::set, "leader-url");
                            return ServerResponse.ok().bodyValue(targetUrl.get());
                        })
                        .GET("/index-url/{nodeIndex}", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            int nodeIndex = Integer.parseInt(request.pathVariable("nodeIndex"));
                            AtomicReference<String> targetUrl = new AtomicReference<>();
                            var ret = redirectFunction.toIndexFunc(nodeIndex, targetUrl::set, "index-url(" + nodeIndex + ")");
                            if (ret == null)
                                return ServerResponse.ok().bodyValue(targetUrl.get());
                            else
                                return ServerResponse.badRequest().bodyValue(ret.getMessage());
                        })
                        .GET("/node-status", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            if (clusterStarter.isPrepared) {
                                NodeStatus status = new NodeStatus(clusterStarter.nodeIndex, clusterStarter.position, clusterStarter.isActivated);
                                return ServerResponse.ok().bodyValue(status);
                            }
                            else {
                                return ServerResponse.badRequest().bodyValue("application is not prepared, get status ignored");
                            }
                        })
                        .PUT("/set-to-leader", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            if (clusterStarter.isPrepared) {
                                clusterService.transition(Position.LEADER);
                                return ServerResponse.ok().build();
                            }
                            else {
                                return ServerResponse.badRequest().bodyValue("application is not prepared, set to leader ignored");
                            }
                        })
                        .PUT("/set-to-follower", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            if (clusterStarter.isPrepared) {
                                clusterService.transition(Position.FOLLOWER);
                                return ServerResponse.ok().build();
                            }
                            else {
                                return ServerResponse.badRequest().bodyValue("application is not prepared, set to follower ignored");
                            }
                        })
                        .GET("/shared-object-map", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(clusterService.sharedObject);
                        })
                        .GET("/shared-object-seq", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(clusterService.sharedObjectSeq);
                        })
                        .POST("/add-cluster-node", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return request.bodyToMono(String.class)
                                    .flatMap(url -> {
                                        if (clusterStarter.nodeTargetUrls.contains(url) || url.equals(clusterStarter.nodeUrl))
                                            return ServerResponse.badRequest().bodyValue("node " + url + " already registered");
                                        else {
                                            clusterStarter.nodeTargetUrls.add(url);
                                            return ServerResponse.ok().build();
                                        }
                                    });
                        })
                        .GET("/get-cluster-urls", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            var ret = new ArrayList<>(clusterStarter.nodeTargetUrls);
                            ret.add(clusterStarter.nodeUrl);
                            return ServerResponse.ok().bodyValue(ret);
                        })
                        .GET("/get-cluster-nodes", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(clusterStarter.getCluster());
                        })
                        .GET("/get-node-index", request -> {
                            log.trace(request.uri().getRawPath() + (request.uri().getRawQuery() != null ? "?" + request.uri().getRawQuery() : ""));
                            return ServerResponse.ok().bodyValue(clusterStarter.nodeIndex);
                        })
        );
    }
}
