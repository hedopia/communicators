package com.sds.communicators.io;

import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.driver.DriverStarter;
import io.netty.channel.Channel;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.LoopResources;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@ConfigurationProperties("io")
public class IoConfiguration {
    private final String driverId;
    private final boolean loadBalancing;
    private final String driverBasePath;
    private final int nodeIndex;
    private final int quorum;
    private final int leaderLostTimeoutSeconds;
    private final int heartbeatSendingIntervalMillis;
    private final Set<String> nodeTargetUrls;

    @Value("${server.port}")
    private final int serverPort;
    private final String clusterBasePath;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private DriverStarter driverStarter = null;

    /** matches the ClusterStarter.start() default that previously created this server */
    private static final int HTTP_SERVER_THREAD_POOL_SIZE = 200;

    private DisposableServer httpServer = null;
    private final Set<Channel> serverChannels = ConcurrentHashMap.newKeySet();

    @Bean
    public DriverStarter driverStarter() throws Throwable {
        driverStarter = DriverStarterDBOutput.builder(
                driverId,
                ClusterStarter.builder(
                                nodeTargetUrls,
                                serverPort,
                                nodeIndex)
                        .setQuorum(quorum)
                        .setLeaderLostTimeoutSeconds(leaderLostTimeoutSeconds)
                        .setHeartbeatSendingIntervalMillis(heartbeatSendingIntervalMillis)
                        .setClusterBasePath(clusterBasePath)
                        .setConnectTimeoutMillis(connectTimeoutMillis)
                        .setReadTimeoutMillis(readTimeoutMillis))
                .setLoadBalancing(loadBalancing)
                .setDriverBasePath(driverBasePath)
                .build();
        // the http server is owned by this application (see the httpServer bean below);
        // this still starts the internal node-to-node gRPC server
        driverStarter.startWithoutHttpServer();
        return driverStarter;
    }

    /**
     * http server owned by this application; binds the cluster/driver REST routes and the
     * driver web UI contributed by driver-starter, instead of letting ClusterStarter create
     * its own server (see driverStarter()'s startWithoutHttpServer())
     */
    @Bean
    public DisposableServer httpServer(DriverStarter driverStarter) {
        httpServer = HttpServer.create()
                .port(serverPort)
                .runOn(LoopResources.create("http", HTTP_SERVER_THREAD_POOL_SIZE, true))
                .doOnConnection(connection -> {
                    serverChannels.add(connection.channel());
                    connection.onDispose(() -> serverChannels.remove(connection.channel()));
                })
                .route(driverStarter.getRoutes())
                .bindNow();
        return httpServer;
    }

    @PreDestroy
    public void disposeHttpServer() {
        if (httpServer == null) return;
        httpServer.disposeNow();
        for (var channel : serverChannels) {
            try {
                channel.close().get();
            } catch (Exception ignored) {}
        }
        serverChannels.clear();
        httpServer = null;
    }
}
