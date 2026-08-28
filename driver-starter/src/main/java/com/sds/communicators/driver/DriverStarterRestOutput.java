package com.sds.communicators.driver;

import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.LoadBalancer;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.server.HttpServerRoutes;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
public class DriverStarterRestOutput extends DriverStarter {

    /** fixed order so that the LoadBalancer's indexes always mean the same target */
    private final List<String> restOutputTargetUrls;
    private final LoadBalancer loadBalancer;
    private final String responsePath;
    private final String responseFormat;
    private final String statusPath;
    private final String statusFormat;

    public static Builder builder(Set<String> restOutputTargetUrls, String responsePath, String responseFormat, String statusPath, String statusFormat, String driverId, ClusterStarter.Builder clusterStarterBuilder) {
        return new Builder(restOutputTargetUrls, responsePath, responseFormat, statusPath, statusFormat, driverId, clusterStarterBuilder);
    }

    public static class Builder extends DriverStarter.Builder {
        private final Set<String> restOutputTargetUrls;
        private final String responsePath;
        private final String responseFormat;
        private final String statusPath;
        private final String statusFormat;

        private Builder(Set<String> restOutputTargetUrls, String responsePath, String responseFormat, String statusPath, String statusFormat, String driverId, ClusterStarter.Builder clusterStarterBuilder) {
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
                    defaultScript,
                    driverEvents,
                    driverBasePath,
                    clusterEvents,
                    routes,
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

        this.restOutputTargetUrls = List.copyOf(restOutputTargetUrls);
        this.loadBalancer = new LoadBalancer(this.restOutputTargetUrls.size());
        this.responsePath = responsePath;
        this.responseFormat = responseFormat;
        this.statusPath = statusPath;
        this.statusFormat = statusFormat;
    }

    @Override
    protected void sendResponse(List<Response> responses, String driverId, int nodeIndex) throws Exception {
        var body = "[" + String.join(",", getResponseFormat(responses, driverId, nodeIndex, responseFormat)) + "]";
        try {
            post(responsePath, body);
        } catch (Throwable e) {
            throw new Exception("rest send responses failed", e);
        }
    }

    @Override
    protected void sendStatus(Status deviceStatus, String driverId, int nodeIndex) throws Exception {
        try {
            post(statusPath, getStatusFormat(deviceStatus, driverId, nodeIndex, statusFormat));
        } catch (Throwable e) {
            throw new Exception("rest send status failed", e);
        }
    }

    /**
     * Posts to one of the configured targets. The LoadBalancer spreads the load, remembers which
     * targets have been failing so it can skip them, and falls back to a skipped one when every
     * other target fails too; it returns the last error when none succeeded.
     */
    private void post(String path, String body) throws Throwable {
        // the cluster's shared NodeHttpClient is reused; the body is already-rendered JSON
        var failure = loadBalancer.run(index ->
                getClusterStarter().getNodeHttpClient().callRaw(restOutputTargetUrls.get(index) + path, "POST", body));
        if (failure != null) throw failure;
    }
}
