package com.sds.communicators.driver;

import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.server.HttpServerRoutes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class DriverStarterNoneOutput extends DriverStarter {

    public static Builder builder(String driverId, ClusterStarter.Builder clusterStarterBuilder) {
        return new Builder(driverId, clusterStarterBuilder);
    }

    public static class Builder extends DriverStarter.Builder {

        private Builder(String driverId, ClusterStarter.Builder clusterStarterBuilder) {
            super(driverId, clusterStarterBuilder);
        }

        @Override
        public DriverStarter build() throws Exception {
            return new DriverStarterNoneOutput(
                    driverId,
                    loadBalancing,
                    defaultScript,
                    driverEvents,
                    driverBasePath,
                    clusterEvents,
                    routes,
                    clusterStarterBuilder);
        }
    }

    private DriverStarterNoneOutput(String driverId,
                            boolean loadBalancing,
                            String defaultScript,
                            DriverEvents driverEvents,
                            String driverBasePath,
                            ClusterEvents clusterEvents,
                            Consumer<HttpServerRoutes> routes,
                            ClusterStarter.Builder clusterStarterBuilder) throws Exception {
        super(driverId,
                loadBalancing,
                defaultScript,
                driverEvents,
                driverBasePath,
                clusterEvents,
                routes,
                clusterStarterBuilder);
    }

    @Override
    protected void sendResponse(List<Response> responses, String driverId, int nodeIndex) {
        for (Response response : responses) {
            var receivedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(response.getReceivedTime()), ZoneId.systemDefault());
            log.debug("[{}] tag-id: {}, value: {}, received-time: {}", response.getDeviceId(), response.getTagId(), response.getValue(), receivedTime);
        }
    }

    @Override
    protected void sendStatus(Status deviceStatus, String driverId, int nodeIndex) {
        log.debug("[{}] status: {}, driver-id: {}, node-index: {}", deviceStatus.getDeviceId(), deviceStatus, driverId, nodeIndex);
    }
}
