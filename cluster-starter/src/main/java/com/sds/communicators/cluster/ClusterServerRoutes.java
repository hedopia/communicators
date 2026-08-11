package com.sds.communicators.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.netty.handler.codec.http.HttpHeaderNames;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
class ClusterServerRoutes {
    private final RedirectFunction redirectFunction;
    private final ClusterStarter clusterStarter;
    private final ClusterService clusterService;
    private final String clusterBasePath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String REDIRECT_TO_LEADER = "/redirect-to-leader";
    private static final String REDIRECT_TO_INDEX = "/redirect-to-index";

    ClusterServerRoutes(RedirectFunction redirectFunction, ClusterStarter clusterStarter, ClusterService clusterService, String clusterBasePath) {
        this.redirectFunction = redirectFunction;
        this.clusterStarter = clusterStarter;
        this.clusterService = clusterService;
        this.clusterBasePath = clusterBasePath;
    }

    void apply(HttpServerRoutes routes) {
        redirect(routes);
        controller(routes);
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
        return request.receive().aggregate().asString(StandardCharsets.UTF_8).defaultIfEmpty("");
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

    private Mono<Void> proxy(HttpServerRequest request, HttpServerResponse response, String uri) {
        return HttpClient.create()
                .headers(headers -> headers.add(request.requestHeaders()))
                .request(request.method())
                .uri(uri)
                .send(request.receive().retain())
                .responseSingle((clientResponse, byteBufMono) ->
                        byteBufMono.asByteArray().defaultIfEmpty(new byte[0])
                                .flatMap(body -> {
                                    response.status(clientResponse.status());
                                    for (var entry : clientResponse.responseHeaders()) {
                                        if (!entry.getKey().equalsIgnoreCase("transfer-encoding") &&
                                                !entry.getKey().equalsIgnoreCase("content-length"))
                                            response.header(entry.getKey(), entry.getValue());
                                    }
                                    return response.sendByteArray(Mono.just(body)).then();
                                }));
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
