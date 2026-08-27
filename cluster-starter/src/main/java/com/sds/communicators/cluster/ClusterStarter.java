package com.sds.communicators.cluster;

import com.sds.communicators.common.type.Position;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.netty.channel.Channel;
import io.reactivex.rxjava3.functions.Consumer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRoutes;
import reactor.netty.resources.LoopResources;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class ClusterStarter {
    int nodeIndex;
    int quorum;
    int leaderLostTimeoutSeconds;
    int heartbeatSendingIntervalMillis;

    private final RedirectFunction redirectFunction;
    private final ClusterService clusterService;
    private final ClusterServerRoutes clusterServerRoutes;
    private final ClusterGrpcService clusterGrpcService;
    private final java.util.function.Consumer<HttpServerRoutes> additionalRoutes;
    private final ClusterClient clusterClient;
    private final ClusterGrpcClient grpcClient;
    private final List<io.grpc.ServerServiceDefinition> grpcServices;
    private final int serverPort;
    final int grpcPortOffset;

    private DisposableServer server = null;
    private io.grpc.Server grpcServer = null;
    private final Set<Channel> serverChannels = ConcurrentHashMap.newKeySet();

    final Set<String> nodeTargetUrls = new HashSet<>();
    final String nodeUrl;

    Position position = null;
    boolean isActivated = false;
    boolean isPrepared = false;

    private boolean isStarted = false;

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
        private String clusterBasePath;
        private int connectTimeoutMillis;
        private int readTimeoutMillis;
        private int grpcPortOffset;
        private List<io.grpc.ServerServiceDefinition> grpcServices;

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
            this.grpcPortOffset = 10000;
            this.grpcServices = null;
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

        public String getClusterBasePath() {
            return clusterBasePath;
        }

        public ClusterStarter.Builder setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public ClusterStarter.Builder setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public ClusterStarter.Builder setGrpcPortOffset(int grpcPortOffset) {
            this.grpcPortOffset = grpcPortOffset;
            return this;
        }

        /**
         * additional gRPC services to register on the cluster gRPC server
         * alongside the cluster internal service
         */
        public ClusterStarter.Builder setGrpcServices(List<io.grpc.ServerServiceDefinition> grpcServices) {
            this.grpcServices = grpcServices;
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
                    readTimeoutMillis,
                    grpcPortOffset,
                    grpcServices);
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
                           int readTimeoutMillis,
                           int grpcPortOffset,
                           List<io.grpc.ServerServiceDefinition> grpcServices) throws Exception {
        clusterClient = new ClusterClient(connectTimeoutMillis, readTimeoutMillis);
        grpcClient = new ClusterGrpcClient(grpcPortOffset, connectTimeoutMillis, readTimeoutMillis);
        this.grpcPortOffset = grpcPortOffset;
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

        redirectFunction = new RedirectFunction(this.nodeTargetUrls, grpcClient, this);
        clusterService = new ClusterService(this, redirectFunction, grpcClient);
        clusterServerRoutes = new ClusterServerRoutes(redirectFunction, this, clusterService, clusterBasePath);
        clusterGrpcService = new ClusterGrpcService(this, clusterService);
        clusterService.clusterEvents.addAll(clusterEvents);
        this.additionalRoutes = routes;
        this.grpcServices = grpcServices;
    }

    public java.util.function.Consumer<HttpServerRoutes> getRoutes() {
        return routes -> {
            clusterServerRoutes.apply(routes);
            if (additionalRoutes != null)
                additionalRoutes.accept(routes);
        };
    }

    private void startServer(int serverThreadPoolSize, boolean httpServer) throws Exception {
        if (server != null)
            server.disposeNow();
        if (grpcServer != null)
            grpcServer.shutdownNow();

        if (httpServer) {
            server = HttpServer.create()
                    .port(serverPort)
                    .runOn(LoopResources.create("http", serverThreadPoolSize, true))
                    .doOnConnection(c -> {
                        serverChannels.add(c.channel());
                        c.onDispose(() -> serverChannels.remove(c.channel()));
                    })
                    .route(r -> getRoutes().accept(r))
                    .bindNow();
        }

        var grpcServerBuilder = NettyServerBuilder.forPort(serverPort + grpcPortOffset)
                .maxInboundMessageSize(Integer.MAX_VALUE)
                .addService(clusterGrpcService.bindService());
        if (grpcServices != null)
            for (var service : grpcServices)
                grpcServerBuilder.addService(service);
        grpcServer = grpcServerBuilder
                .build()
                .start();

        for (String targetUrl : nodeTargetUrls) {
            try {
                var index = grpcClient.getNodeIndex(targetUrl);
                if (nodeIndex == index)
                    throw new Exception("this node (" + nodeUrl + ") and (" + targetUrl + "), node-index(" + index + ") duplicated");
            } catch (Exception ignored) {}
        }
        log.info("(node-index: {}, url: {}) started", nodeIndex, nodeUrl);
    }

    public void startWithoutHttpServer() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            startServer(-1, false);
            clusterService.start();
        }
    }

    public void start() throws Throwable {
        int DEFAULT_SERVER_THREAD_POOL_SIZE = 200;
        start(DEFAULT_SERVER_THREAD_POOL_SIZE);
    }

    public void start(int serverThreadPoolSize) throws Throwable {
        if (!isStarted) {
            isStarted = true;
            startServer(serverThreadPoolSize, true);
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
        if (grpcServer != null) {
            grpcServer.shutdownNow();
            try {
                grpcServer.awaitTermination();
            } catch (InterruptedException ignored) {}
            grpcServer = null;
        }
        clusterClient.dispose();
        grpcClient.dispose();
        isStarted = false;
        log.info("cluster-starter disposed");
    }

    public int getNodeIndex() {
        return nodeIndex;
    }
    public int getQuorum() {
        return quorum;
    }
    public Set<String> getNodeTargetUrls() {
        return nodeTargetUrls;
    }
    public Position getPosition() {
        return position;
    }
    public boolean isActivated() {
        return isActivated;
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

    public <U> void loadBalancedClient(Set<String> urls, Class<U> api, Consumer<U> run) throws Throwable {
        clusterClient.loadBalancedClient(urls, api, run);
    }

    public <U> U getClient(String url, Class<U> api) {
        return clusterClient.getClient(url, api);
    }

    /**
     * unary gRPC call to the given node URL, reusing the internal
     * channel cache and read-timeout deadline
     */
    public <Req, Res> Res grpcCall(String nodeUrl, io.grpc.MethodDescriptor<Req, Res> method, Req request) {
        return grpcClient.call(nodeUrl, method, request);
    }

    public Position getPosition(int nodeIndex) throws Throwable {
        return clusterService.getPosition(nodeIndex);
    }
}
