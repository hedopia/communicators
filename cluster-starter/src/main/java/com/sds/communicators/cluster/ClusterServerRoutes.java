package com.sds.communicators.cluster;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
class ClusterServerRoutes {
    private static final String REDIRECT_TO_LEADER = "/redirect-to-leader";
    private static final String REDIRECT_TO_INDEX = "/redirect-to-index";

    HttpServerRoutes redirect(HttpServerRoutes routes, RedirectFunction redirectFunction) {
        return routes
                .route(request ->
                                request.uri().startsWith(REDIRECT_TO_LEADER),
                        (request, response) -> {
                            AtomicReference<String> targetUrl = new AtomicReference<>();
                            redirectFunction.toLeaderFuncConfirmed(targetUrl::set, request.uri());
                            return redirect(request, response, targetUrl.get() + request.uri().substring(REDIRECT_TO_LEADER.length()));
                        })
                .route(request ->
                                request.uri().startsWith(REDIRECT_TO_INDEX),
                        (request, response) -> {
                            request.
                            AtomicReference<String> targetUrl = new AtomicReference<>();
                            redirectFunction.toLeaderFuncConfirmed(targetUrl::set, request.uri());
                            return redirect(request, response, targetUrl.get() + request.uri().substring(REDIRECT_TO_LEADER.length()));
                        });
    }

    private Mono<Void> redirect(HttpServerRequest request, HttpServerResponse response, String uri) {
        return HttpClient.create()
                .headers(headers -> request.requestHeaders())
                .request(request.method())
                .uri(uri)
                .send(request.receive())
                .responseSingle((clientResponse, byteBufMono) ->
                        response.status(clientResponse.status())
                                .headers(clientResponse.responseHeaders())
                                .send(byteBufMono).then());
    }
}
