package com.sds.communicators.io;

import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.driver.DriverStarter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Set;

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
        // the http server is owned by Spring Boot (routes are contributed by the
        // driverRouteProvider bean below); this still starts the node-to-node gRPC server
        driverStarter.startWithoutHttpServer();
        return driverStarter;
    }
}
