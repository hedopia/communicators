package com.sds.communicators.cluster;

import com.sds.communicators.common.type.Position;
import io.netty.channel.Channel;
import io.reactivex.rxjava3.functions.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRoutes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ClusterStarter {
    @Getter
    int nodeIndex;
    @Getter
    int quorum;
    int leaderLostTimeoutSeconds;
    int heartbeatSendingIntervalMillis;

    private final RedirectFunction redirectFunction;
    private final ClusterService clusterService;
    private final ClusterServerRoutes clusterServerRoutes;
    private final java.util.function.Consumer<HttpServerRoutes> additionalRoutes;
    @Getter
    private final NodeHttpClient nodeHttpClient;
    private final ClusterInternalClient internalClient;
    private final int serverPort;

    private DisposableServer server = null;
    private final Set<Channel> serverChannels = ConcurrentHashMap.newKeySet();

    @Getter
    final Set<String> nodeTargetUrls = new HashSet<>();
    final String nodeUrl;

    @Getter
    Position position = null;
    @Getter
    boolean isActivated = false;
    boolean isPrepared = false;

    private boolean isStarted = false;

    /**
     * Body limit for an h2c upgrade request. Reactor Netty defaults it to 0, which rejects any
     * upgrade carrying a body with 413 - and the first internal call to a node is often a POST
     * (a shared-object merge). Once upgraded the limit no longer applies, since later requests
     * are HTTP/2 streams.
     */
    private static final int H2C_MAX_CONTENT_LENGTH = 64 * 1024 * 1024;

    public static Builder builder(Set<String> nodeTargetUrls, int serverPort, int nodeIndex) {
        return new Builder(nodeTargetUrls, serverPort, nodeIndex);
    }

    public static class Builder {
        private final Set<String> nodeTargetUrls;
        private final int serverPort;
        private final int nodeIndex;
        private int quorum;
        private int leaderLostTimeoutSeconds;
        private int heartbeatSendingIntervalMillis;
        private ClusterEvents clusterEvents;
        private java.util.function.Consumer<HttpServerRoutes> routes;
        @Getter
        private String clusterBasePath;
        @Getter
        private int connectTimeoutMillis;
        @Getter
        private int readTimeoutMillis;

        private Builder(Set<String> nodeTargetUrls, int serverPort, int nodeIndex) {
            this.nodeTargetUrls = nodeTargetUrls;
            this.serverPort = serverPort;
            this.nodeIndex = nodeIndex;
            this.quorum = 0;
            this.leaderLostTimeoutSeconds = 20;
            this.heartbeatSendingIntervalMillis = 2000;
            this.clusterEvents = null;
            this.routes = null;
            this.clusterBasePath = "/cluster";
            this.connectTimeoutMillis = 1000;
            this.readTimeoutMillis = 60000;
        }

        public ClusterStarter.Builder setQuorum(int quorum) {
            this.quorum = quorum;
            return this;
        }

        public ClusterStarter.Builder setLeaderLostTimeoutSeconds(int leaderLostTimeoutSeconds) {
            this.leaderLostTimeoutSeconds = leaderLostTimeoutSeconds;
            return this;
        }

        public ClusterStarter.Builder setHeartbeatSendingIntervalMillis(int heartbeatSendingIntervalMillis) {
            this.heartbeatSendingIntervalMillis = heartbeatSendingIntervalMillis;
            return this;
        }

        public ClusterStarter.Builder setClusterEvents(ClusterEvents clusterEvents) {
            this.clusterEvents = clusterEvents;
            return this;
        }

        public ClusterStarter.Builder setRoutes(java.util.function.Consumer<HttpServerRoutes> routes) {
            this.routes = routes;
            return this;
        }

        public ClusterStarter.Builder setClusterBasePath(String clusterBasePath) {
            this.clusterBasePath = clusterBasePath;
            return this;
        }

        public ClusterStarter.Builder setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public ClusterStarter.Builder setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public ClusterStarter build() throws Exception {
            return new ClusterStarter(nodeTargetUrls,
                    serverPort,
                    nodeIndex,
                    quorum,
                    leaderLostTimeoutSeconds,
                    heartbeatSendingIntervalMillis,
                    clusterEvents,
                    routes,
                    clusterBasePath,
                    connectTimeoutMillis,
                    readTimeoutMillis);
        }
    }

    private ClusterStarter(Set<String> nodeTargetUrls,
                           int serverPort,
                           int nodeIndex,
                           int quorum,
                           int leaderLostTimeoutSeconds,
                           int heartbeatSendingIntervalMillis,
                           ClusterEvents clusterEvents,
                           java.util.function.Consumer<HttpServerRoutes> routes,
                           String clusterBasePath,
                           int connectTimeoutMillis,
                           int readTimeoutMillis) throws Exception {
        nodeHttpClient = new NodeHttpClient(connectTimeoutMillis, readTimeoutMillis);
        internalClient = new ClusterInternalClient(nodeHttpClient, clusterBasePath);
        this.nodeIndex = nodeIndex;
        this.quorum = quorum;
        this.leaderLostTimeoutSeconds = leaderLostTimeoutSeconds;
        this.heartbeatSendingIntervalMillis = heartbeatSendingIntervalMillis;
        this.serverPort = serverPort;

        Set<Channel> channels = ConcurrentHashMap.newKeySet();
        var server = HttpServer.create()
                .port(serverPort)
                .doOnConnection(c -> {
                    channels.add(c.channel());
                    c.onDispose(() -> channels.remove(c.channel()));
                })
                .route(r ->
                        r.get("/index",
                                (request, response) -> response.sendString(Mono.just(Integer.toString(nodeIndex))))
                )
                .bindNow();
        Set<String> nodeUrls = new HashSet<>();
        try {
            for (String targetUrl : nodeTargetUrls) {
                try {
                    var index = HttpClient.create().get().uri(targetUrl + "/index").responseSingle((resp, bytes) -> bytes.asString()).block();
                    if (Objects.equals(index, Integer.toString(nodeIndex)))
                        nodeUrls.add(targetUrl);
                } catch (Exception ignored) {}
            }
            if (nodeUrls.isEmpty())
                throw new Exception("can't define node url within node-target-urls: " + nodeTargetUrls);
            this.nodeUrl = nodeUrls.stream().findFirst().get();
        } finally {
            server.disposeNow();
            for (var channel : channels)
                channel.close().get();
        }

        this.nodeTargetUrls.addAll(nodeTargetUrls.stream().filter(url -> !nodeUrls.contains(url)).collect(Collectors.toSet()));

        redirectFunction = new RedirectFunction(this.nodeTargetUrls, internalClient, this);
        clusterService = new ClusterService(this, redirectFunction, internalClient);
        clusterServerRoutes = new ClusterServerRoutes(redirectFunction, this, clusterService, clusterBasePath,
                connectTimeoutMillis, readTimeoutMillis);
        clusterService.clusterEvents.addAll(clusterEvents);
        this.additionalRoutes = routes;
    }

    public java.util.function.Consumer<HttpServerRoutes> getRoutes() {
        return routes -> {
            // every handler below is blocking, so dispatch each request to its own worker thread
            // instead of running it on the event loop that accepted it
            var dispatched = RouteDispatcher.perRequestThread(routes);
            clusterServerRoutes.apply(dispatched);
            if (additionalRoutes != null)
                additionalRoutes.accept(dispatched);
        };
    }

    private void startServer(boolean httpServer) throws Exception {
        if (server != null)
            server.disposeNow();

        if (httpServer) {
            server = HttpServer.create()
                    .port(serverPort)
                    // h2c alongside HTTP/1.1 on the same port: node-to-node calls upgrade and
                    // multiplex, browsers and any HTTP/1.1 client keep working unchanged
                    .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
                    .httpRequestDecoder(spec -> spec.h2cMaxContentLength(H2C_MAX_CONTENT_LENGTH))
                    .doOnConnection(c -> {
                        serverChannels.add(c.channel());
                        c.onDispose(() -> serverChannels.remove(c.channel()));
                    })
                    .route(r -> getRoutes().accept(r))
                    .bindNow();
        }

        for (String targetUrl : nodeTargetUrls) {
            try {
                var index = internalClient.getNodeIndex(targetUrl);
                if (nodeIndex == index)
                    throw new Exception("this node (" + nodeUrl + ") and (" + targetUrl + "), node-index(" + index + ") duplicated");
            } catch (Exception ignored) {}
        }
        log.info("(node-index: {}, url: {}) started", nodeIndex, nodeUrl);
    }

    public void startWithoutHttpServer() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            startServer(false);
            clusterService.start();
        }
    }

    public void start() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            startServer(true);
            clusterService.start();
        }
    }

    public void dispose() {
        clusterService.dispose();
        if (server != null) {
            server.disposeNow();
            for (Channel channel : serverChannels) {
                try {
                    channel.close().get();
                } catch (Exception ignored) {}
            }
            server = null;
        }
        nodeHttpClient.dispose();
        isStarted = false;
        log.info("cluster-starter disposed");
    }

    public Set<Integer> getCluster() {
        return clusterService.getCluster();
    }

    public void mergeSharedObject(Map<String, Object> obj) { clusterService.mergeSharedObject(obj); }

    public void mergeSharedObject(Object value, String... path) { clusterService.mergeSharedObject(value, path); }

    public void deleteSharedObject(List<List<String>> paths) { clusterService.deleteSharedObject(paths); }

    public void deleteSharedObject(String... path) { clusterService.deleteSharedObject(path); }

    public Object getItem(int nodeIndex, String[] path) { return clusterService.getItem(nodeIndex, path); }

    public Map<Integer, Map<String, Object>> getSharedObjectMap() {
        return clusterService.sharedObject;
    }

    public Map<String, Object> getSharedObject() {
        return clusterService.sharedObject.get(nodeIndex);
    }

    public void forceToLeader() {
        clusterService.forceToLeader();
    }

    public void forceToFollower() {
        clusterService.forceToFollower();
    }

    public void toLeaderFuncConfirmed(Consumer<String> consumer, String name) {
        redirectFunction.toLeaderFuncConfirmed(consumer, name);
    }

    public Throwable toLeaderFunc(Consumer<String> consumer, String name) {
        return redirectFunction.toLeaderFunc(consumer, name);
    }

    public Throwable toIndexFunc(int nodeIndex, Consumer<String> consumer, String name) {
        return redirectFunction.toIndexFunc(nodeIndex, consumer, name);
    }

    public void toAllFunc(Consumer<String> consumer, String name) {
        redirectFunction.toAllFunc(consumer, name);
    }

    public <U> void parallelExecute(Collection<U> collection, Consumer<U> consumer) {
        redirectFunction.parallelExecute(collection, consumer);
    }

    public Position getPosition(int nodeIndex) throws Throwable {
        return clusterService.getPosition(nodeIndex);
    }
}
