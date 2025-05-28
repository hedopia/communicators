package com.sds.communicators.cluster;

import com.sds.communicators.common.type.Position;
import io.netty.channel.Channel;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

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
    private final ClusterServer clusterServer;
    private final ClusterClient clusterClient;

    final Set<String> nodeTargetUrls = new HashSet<>();
    final String nodeUrl;

    Position position = null;
    boolean isActivated = false;
    boolean isPrepared = false;

    private boolean isStarted = false;

    public static class Builder {
        private final Set<String> nodeTargetUrls;
        private final int serverPort;
        private final int nodeIndex;
        private int quorum;
        private int leaderLostTimeoutSeconds;
        private int heartbeatSendingIntervalMillis;
        private ClusterEvents clusterEvents;
        private RouterFunctions.Builder routerFunctionBuilder;
        private String clusterBasePath;
        private int connectTimeoutMillis;
        private int readTimeoutMillis;

        public Builder(Set<String> nodeTargetUrls, int serverPort, int nodeIndex) {
            this.nodeTargetUrls = nodeTargetUrls;
            this.serverPort = serverPort;
            this.nodeIndex = nodeIndex;
            this.quorum = 0;
            this.leaderLostTimeoutSeconds = 20;
            this.heartbeatSendingIntervalMillis = 2000;
            this.clusterEvents = null;
            this.routerFunctionBuilder = null;
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

        public ClusterStarter.Builder setRouterFunctionBuilder(RouterFunctions.Builder routerFunctionBuilder) {
            this.routerFunctionBuilder = routerFunctionBuilder;
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
                    routerFunctionBuilder,
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
                          RouterFunctions.Builder routerFunctionBuilder,
                          String clusterBasePath,
                          int connectTimeoutMillis,
                          int readTimeoutMillis) throws Exception {
        clusterClient = new ClusterClient(connectTimeoutMillis, readTimeoutMillis);
        this.nodeIndex = nodeIndex;
        this.quorum = quorum;
        this.leaderLostTimeoutSeconds = leaderLostTimeoutSeconds;
        this.heartbeatSendingIntervalMillis = heartbeatSendingIntervalMillis;

        Set<Channel> channels = ConcurrentHashMap.newKeySet();
        var server = HttpServer.create()
                .port(serverPort)
                .doOnConnection(c -> {
                    channels.add(c.channel());
                    c.onDispose(() -> channels.remove(c.channel()));
                })
                .route(routes ->
                        routes.get("/index",
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
            for (Channel channel : channels)
                channel.close().get();
        }

        this.nodeTargetUrls.addAll(nodeTargetUrls.stream().filter(url -> !nodeUrls.contains(url)).collect(Collectors.toSet()));

        redirectFunction = new RedirectFunction(this.nodeTargetUrls, clusterClient, this, clusterBasePath);
        clusterService = new ClusterService(this, redirectFunction, clusterClient, clusterBasePath);
        clusterServer = new ClusterServer(serverPort, redirectFunction, this, clusterService, clusterBasePath);
        clusterService.clusterEvents.addAll(clusterEvents);
        clusterServer.addRouterFunctionBuilder(routerFunctionBuilder);
    }

    public RouterFunctions.Builder getRouterFunction() {
        return clusterServer.routerFunctionBuilder;
    }

    public void startWithoutHttpServer() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            clusterServer.start(-1, false);
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
            clusterServer.start(serverThreadPoolSize, true);
            clusterService.start();
        }
    }

    public void dispose() {
        clusterService.dispose();
        clusterServer.dispose();
        clusterClient.dispose();
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

    public void syncExecute(Action action) {
        redirectFunction.syncExecute(action);
    }

    public <U> void loadBalancedClient(Set<String> urls, Class<U> api, Consumer<U> run) throws Throwable {
        clusterClient.loadBalancedClient(urls, api, run);
    }

    public <U> U getClient(String url, Class<U> api) {
        return clusterClient.getClient(url, api);
    }

    public Position getPosition(int nodeIndex) throws Throwable {
        return clusterService.getPosition(nodeIndex);
    }
}