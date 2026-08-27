package com.sds.communicators.cluster;

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
 * Runs route handlers on their own worker thread instead of on a Reactor Netty event loop.
 * <p>
 * The cluster and driver handlers are blocking by design: they wait on cluster redirects,
 * device connect/disconnect and shared-object fan-out. Executed on an event loop they would
 * stall every other request on that loop, including inbound heartbeats. Dispatching each
 * request to its own worker thread gives the thread-per-request model of a servlet container
 * without a fixed pool size, and leaves the event loops free for I/O.
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
     * Wraps {@code routes} so that every handler registered afterwards runs on a worker thread.
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

    private static final class PerRequestThreadRoutes implements HttpServerRoutes {
        private final HttpServerRoutes delegate;

        private PerRequestThreadRoutes(HttpServerRoutes delegate) {
            this.delegate = delegate;
        }

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
