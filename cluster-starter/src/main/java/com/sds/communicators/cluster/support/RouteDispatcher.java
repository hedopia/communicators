package com.sds.communicators.cluster.support;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpRouteHandlerMetadata;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Runs route handlers on their own worker thread instead of on a Reactor Netty event loop:
 * the cluster and driver handlers are blocking (cluster redirects, device connect/disconnect,
 * shared-object fan-out) and on an event loop they would stall every other request on that
 * loop, including inbound heartbeats.
 */
public final class RouteDispatcher {
    /**
     * Unbounded pool: a thread per in-flight request, reused afterwards and reclaimed when idle.
     * <p>
     * Virtual threads cannot be used here. GraalPy creates a polyglot context per device while a
     * connect request is being handled, and Truffle rejects that on a virtual thread when its
     * optimizing runtime is active ("Using polyglot contexts on Java virtual threads is currently
     * not supported with an optimizing Truffle runtime"). Switching Truffle to its default runtime
     * would remove the restriction but also the runtime compilation the GraalVM setup exists for.
     */
    private static final AtomicLong THREAD_SEQ = new AtomicLong();
    private static final Scheduler WORKER = Schedulers.fromExecutorService(
            Executors.newCachedThreadPool(runnable -> {
                var thread = new Thread(runnable, "route-" + THREAD_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }), "route");

    private RouteDispatcher() {}

    /**
     * All the verb helpers ({@code get}, {@code post}, ...) delegate to
     * {@link HttpServerRoutes#route}, so wrapping that one method covers every registration.
     */
    public static HttpServerRoutes perRequestThread(HttpServerRoutes routes) {
        return new PerRequestThreadRoutes(routes);
    }

    /**
     * Moves the continuation of {@code source} onto a worker thread. Needed for handlers that
     * read the request body first: the body arrives on an event loop, so without this the code
     * after the read would run there again.
     */
    public static <T> Mono<T> continueOnWorker(Mono<T> source) {
        return source.publishOn(WORKER);
    }

    private record PerRequestThreadRoutes(HttpServerRoutes delegate) implements HttpServerRoutes {

        @Override
            public HttpServerRoutes route(Predicate<? super HttpServerRequest> condition,
                                          BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
                delegate.route(condition, (request, response) ->
                        // defer so the handler body (the blocking part) runs at subscription time,
                        // which subscribeOn places on a worker thread
                        Mono.defer(() -> Mono.from(handler.apply(request, response)))
                                .subscribeOn(WORKER));
                return this;
            }

            @Override
            public HttpServerRoutes directory(String uri, Path directory, Function<HttpServerResponse, HttpServerResponse> interceptor) {
                delegate.directory(uri, directory, interceptor);
                return this;
            }

            @Override
            public HttpServerRoutes removeIf(Predicate<? super HttpRouteHandlerMetadata> condition) {
                delegate.removeIf(condition);
                return this;
            }

            @Override
            public HttpServerRoutes comparator(Comparator<HttpRouteHandlerMetadata> comparator) {
                delegate.comparator(comparator);
                return this;
            }

            @Override
            public HttpServerRoutes noComparator() {
                delegate.noComparator();
                return this;
            }

            @Override
            public Publisher<Void> apply(HttpServerRequest request, HttpServerResponse response) {
                return delegate.apply(request, response);
            }
        }
}
