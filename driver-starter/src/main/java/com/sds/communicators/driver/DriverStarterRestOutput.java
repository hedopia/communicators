package com.sds.communicators.driver;

import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import feign.Param;
import feign.RequestLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.server.RouterFunctions;

import java.util.List;
import java.util.Set;

@Slf4j
public class DriverStarterRestOutput extends DriverStarter {

    private final Set<String> restOutputTargetUrls;
    private final String responsePath;
    private final String responseFormat;
    private final String statusPath;
    private final String statusFormat;

    public static class Builder extends DriverStarter.Builder {
        private final Set<String> restOutputTargetUrls;
        private final String responsePath;
        private final String responseFormat;
        private final String statusPath;
        private final String statusFormat;

        public Builder(Set<String> restOutputTargetUrls, String responsePath, String responseFormat, String statusPath, String statusFormat, String driverId, ClusterStarter.Builder clusterStarterBuilder) {
            super(driverId, clusterStarterBuilder);
            this.restOutputTargetUrls = restOutputTargetUrls;
            this.responsePath = responsePath;
            this.responseFormat = responseFormat;
            this.statusPath = statusPath;
            this.statusFormat = statusFormat;
        }

        @Override
        public DriverStarter build() throws Exception {
            return new DriverStarterRestOutput(
                    restOutputTargetUrls,
                    responsePath,
                    responseFormat,
                    statusPath,
                    statusFormat,
                    driverId,
                    loadBalancing,
                    reconnectWhenSplitBrainResolved,
                    defaultScript,
                    driverEvents,
                    driverBasePath,
                    clusterEvents,
                    routerFunctionBuilder,
                    clusterStarterBuilder);
        }
    }

    private DriverStarterRestOutput(Set<String> restOutputTargetUrls,
                                   String responsePath,
                                   String responseFormat,
                                   String statusPath,
                                   String statusFormat,
                                   String driverId,
                                   boolean loadBalancing,
                                   boolean reconnectWhenSplitBrainResolved,
                                   String defaultScript,
                                   DriverEvents driverEvents,
                                   String driverBasePath,
                                   ClusterEvents clusterEvents,
                                   RouterFunctions.Builder routerFunctionBuilder,
                                   ClusterStarter.Builder clusterStarterBuilder) throws Exception {
        super(driverId,
                loadBalancing,
                reconnectWhenSplitBrainResolved,
                defaultScript,
                driverEvents,
                driverBasePath,
                clusterEvents,
                routerFunctionBuilder,
                clusterStarterBuilder);

        this.restOutputTargetUrls = restOutputTargetUrls;
        this.responsePath = responsePath;
        this.responseFormat = responseFormat;
        this.statusPath = statusPath;
        this.statusFormat = statusFormat;
    }

    @Override
    void sendResponse(List<Response> responses, String driverId, int nodeIndex) throws Exception {
        try {
            getClusterStarter().loadBalancedClient(restOutputTargetUrls,
                    DriverRestClientApi.class,
                    client -> {
                        var response = String.join(",", getResponseFormat(responses, driverId, nodeIndex, responseFormat));
                        client.sendResponse(responsePath, "[" + response + "]");
                    }
            );
        } catch (Throwable e) {
            throw new Exception("rest send responses failed", e);
        }
    }

    @Override
    void sendStatus(Status deviceStatus, String driverId, int nodeIndex) throws Exception {
        try {
            getClusterStarter().loadBalancedClient(restOutputTargetUrls,
                    DriverRestClientApi.class,
                    client -> client.sendStatus(statusPath,
                            getStatusFormat(deviceStatus, driverId, nodeIndex, statusFormat))
            );
        } catch (Throwable e) {
            throw new Exception("rest send status failed", e);
        }
    }

    interface DriverRestClientApi {
        @RequestLine("POST {responsePath}")
        void sendResponse(@Param("responsePath") String responsePath, String response);

        @RequestLine("POST {statusPath}")
        void sendStatus(@Param("statusPath") String statusPath, String status);
    }
}
