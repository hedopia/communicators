package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import com.sds.communicators.common.type.StatusCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;
import reactor.netty.http.server.HttpServerRoutes;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public abstract class DriverStarter {
    private final String driverId;
    final boolean loadBalancing;
    final String defaultScript;
    final ObjectMapper objectMapper = new ObjectMapper();

    protected abstract void sendResponse(List<Response> responses, String driverId, int nodeIndex) throws Exception;
    protected abstract void sendStatus(Status deviceStatus, String driverId, int nodeIndex) throws Exception;

    private final ClusterStarter clusterStarter;
    private final DriverService driverService;

    private boolean isStarted = false;

    public static abstract class Builder {
        protected final String driverId;
        protected boolean loadBalancing;
        protected String defaultScript;
        protected DriverEvents driverEvents;
        protected String driverBasePath;
        protected ClusterEvents clusterEvents;
        protected Consumer<HttpServerRoutes> routes;
        protected final ClusterStarter.Builder clusterStarterBuilder;

        protected Builder(String driverId, ClusterStarter.Builder clusterStarterBuilder) {
            this.driverId = driverId;
            this.loadBalancing = true;
            this.defaultScript = "";
            this.driverEvents = null;
            this.driverBasePath = "/driver";
            this.clusterEvents = null;
            this.routes = null;
            this.clusterStarterBuilder = clusterStarterBuilder;
        }

        public DriverStarter.Builder setLoadBalancing(boolean loadBalancing) {
            this.loadBalancing = loadBalancing;
            return this;
        }

        public DriverStarter.Builder setDefaultScript(String defaultScript) {
            this.defaultScript = defaultScript;
            return this;
        }

        public DriverStarter.Builder setDriverEvents(DriverEvents driverEvents) {
            this.driverEvents = driverEvents;
            return this;
        }

        public DriverStarter.Builder setDriverBasePath(String driverBasePath) {
            this.driverBasePath = driverBasePath;
            return this;
        }

        public DriverStarter.Builder setClusterEvents(ClusterEvents clusterEvents) {
            this.clusterEvents = clusterEvents;
            return this;
        }

        public DriverStarter.Builder setRoutes(Consumer<HttpServerRoutes> routes) {
            this.routes = routes;
            return this;
        }

        public abstract DriverStarter build() throws Exception;
    }

    protected DriverStarter(String driverId,
                  boolean loadBalancing,
                  String defaultScript,
                  DriverEvents driverEvents,
                  String driverBasePath,
                  ClusterEvents clusterEvents,
                  Consumer<HttpServerRoutes> routes,
                  ClusterStarter.Builder clusterStarterBuilder) throws Exception {
        this.driverId = driverId;
        this.loadBalancing = loadBalancing;
        this.defaultScript = defaultScript;
        driverService = new DriverService(this, driverBasePath);
        clusterStarter = clusterStarterBuilder
                .setClusterEvents(driverService.clusterEvents().addAll(clusterEvents))
                .setRoutes(DriverServerRoutes.getDriverServerRoutes(this, driverService, driverBasePath, clusterStarterBuilder.getClusterBasePath(), routes))
                .build();
        driverService.clusterStarter = clusterStarter;
        // the cluster's probe timeout: two heartbeat intervals (at least 1 s), never longer than a regular call
        driverService.probeTimeout = Duration.ofMillis(Math.min(Math.max(2L * clusterStarter.getHeartbeatSendingIntervalMillis(), 1000L),
                clusterStarterBuilder.getReadTimeoutMillis()));
        driverService.readTimeout = Duration.ofMillis(clusterStarterBuilder.getReadTimeoutMillis());
        driverService.driverEvents.addAll(driverEvents);
    }

    public void startWithoutHttpServer() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            clusterStarter.startWithoutHttpServer();
            driverService.start();
        }
    }

    public void start() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            clusterStarter.start();
            driverService.start();
        }
    }

    public void dispose() {
        log.info("try to dispose driver-starter");
        clusterStarter.dispose();
        driverService.dispose();
        isStarted = false;
        log.info("driver-starter disposed");
    }

    public ClusterStarter getClusterStarter() {
        return clusterStarter;
    }

    public Consumer<HttpServerRoutes> getRoutes() {
        return clusterStarter.getRoutes();
    }

    List<String> getResponseFormat(List<Response> responses, String driverId, int nodeIndex, String responseFormat) throws JsonProcessingException {
        var ret = new ArrayList<String>();
        for (Response response : responses) {
            ret.add(StringSubstitutor.replace(responseFormat, ImmutableMap.of(
                    "deviceId", objectMapper.writeValueAsString(response.getDeviceId()),
                    "tagId", objectMapper.writeValueAsString(response.getTagId()),
                    "value", objectMapper.writeValueAsString(response.getValue()),
                    "driverId", objectMapper.writeValueAsString(driverId),
                    "nodeIndex", nodeIndex,
                    "receivedTime", response.getReceivedTime())));
        }
        return ret;
    }

    String getStatusFormat(Status deviceStatus, String driverId, int nodeIndex, String statusFormat) throws JsonProcessingException {
        return StringSubstitutor.replace(statusFormat, ImmutableMap.of(
                "deviceId", objectMapper.writeValueAsString(deviceStatus.getDeviceId()),
                "status", objectMapper.writeValueAsString(deviceStatus.getStatus().name()),
                "driverId", objectMapper.writeValueAsString(driverId),
                "nodeIndex", nodeIndex,
                "issuedTime", deviceStatus.getIssuedTime()));
    }

    void addDevices(Map<String, Device> deviceMap) throws JsonProcessingException {
        if (deviceMap.isEmpty()) return;
        var devices = new HashMap<String, Object>();
        for (var entry : deviceMap.entrySet()) {
            devices.put(entry.getKey(),
                    objectMapper.readValue(objectMapper.writeValueAsString(entry.getValue()), new TypeReference<Map<String, Object>>() {}));
        }
        clusterStarter.mergeSharedObject(devices);
    }

    void deleteDevices(Map<String, String> deleteResults) {
        Set<String> ownKeys = clusterStarter.readSharedObject(objects -> {
            var own = objects.get(clusterStarter.getNodeIndex());
            return own == null ? Set.of() : new HashSet<>(own.keySet());
        });
        var deviceIds = deleteResults.keySet()
                .stream()
                .filter(deviceId -> !driverService.driverProtocols.containsKey(deviceId) && ownKeys.contains(deviceId))
                .collect(Collectors.toList());
        if (deviceIds.isEmpty()) return;
        clusterStarter.deleteSharedObject(deviceIds.stream().map(Collections::singletonList).collect(Collectors.toList()));
    }

    /**
     * A node's shared object maps deviceId to the device setting ({@link #addDevices}), with script data under its
     * "data" key; an entry without "id"/"connectionUrl" is script data left without a registration, not a device.
     */
    static boolean isDeviceEntry(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey("id") && map.containsKey("connectionUrl");
    }

    public String getDriverId() {
        return driverId;
    }

    public Set<Device> getRegisteredDevices() {
        return driverService.driverProtocols.values().stream().map(protocol -> protocol.device).collect(Collectors.toSet());
    }

    public Map<Integer, Set<String>> getDeviceIdMap() {
        return clusterStarter.readSharedObject(objects -> {
            var ret = new HashMap<Integer, Set<String>>();
            for (var node : objects.entrySet()) {
                var deviceIds = new HashSet<String>();
                for (var entry : node.getValue().entrySet()) {
                    if (isDeviceEntry(entry.getValue()))
                        deviceIds.add(entry.getKey());
                }
                ret.put(node.getKey(), deviceIds);
            }
            return ret;
        });
    }

    public Map<String, StatusCode> getDeviceStatus() {
        return driverService.getDeviceStatus();
    }
}
