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
import org.apache.commons.io.FileUtils;
import org.apache.commons.text.StringSubstitutor;
import org.python.core.PrePy;
import org.python.core.Py;
import org.python.core.PyString;
import org.springframework.web.reactive.function.server.RouterFunctions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

@Slf4j
public abstract class DriverStarter {
    /**
     * driver id
     */
    private final String driverId;

    /**
     * load balancing mode
     */
    final boolean loadBalancing;
    final boolean reconnectWhenSplitBrainResolved;
    final String defaultScript;
    final ObjectMapper objectMapper = new ObjectMapper();

    abstract void sendResponse(List<Response> responses, String driverId, int nodeIndex) throws Exception;
    abstract void sendStatus(Status deviceStatus, String driverId, int nodeIndex) throws Exception;

    private final ClusterStarter clusterStarter;
    private final DriverService driverService;

    private boolean isStarted = false;

    public static abstract class Builder {
        protected final String driverId;
        protected boolean loadBalancing;
        protected boolean reconnectWhenSplitBrainResolved;
        protected String defaultScript;
        protected DriverEvents driverEvents;
        protected String driverBasePath;
        protected ClusterEvents clusterEvents;
        protected RouterFunctions.Builder routerFunctionBuilder;
        protected final ClusterStarter.Builder clusterStarterBuilder;

        protected Builder(String driverId, ClusterStarter.Builder clusterStarterBuilder) {
            this.driverId = driverId;
            this.loadBalancing = true;
            this.reconnectWhenSplitBrainResolved = false;
            this.defaultScript = "";
            this.driverEvents = null;
            this.driverBasePath = "/driver";
            this.clusterEvents = null;
            this.routerFunctionBuilder = null;
            this.clusterStarterBuilder = clusterStarterBuilder;
        }

        public DriverStarter.Builder setLoadBalancing(boolean loadBalancing) {
            this.loadBalancing = loadBalancing;
            return this;
        }

        public DriverStarter.Builder setReconnectWhenSplitBrainResolved(boolean reconnectWhenSplitBrainResolved) {
            this.reconnectWhenSplitBrainResolved = reconnectWhenSplitBrainResolved;
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

        public DriverStarter.Builder setRouterFunctionBuilder(RouterFunctions.Builder routerFunctionBuilder) {
            this.routerFunctionBuilder = routerFunctionBuilder;
            return this;
        }

        public abstract DriverStarter build() throws Exception;
    }

    DriverStarter(String driverId,
                  boolean loadBalancing,
                  boolean reconnectWhenSplitBrainResolved,
                  String defaultScript,
                  DriverEvents driverEvents,
                  String driverBasePath,
                  ClusterEvents clusterEvents,
                  RouterFunctions.Builder routerFunctionBuilder,
                  ClusterStarter.Builder clusterStarterBuilder) throws Exception {
        Py.initPython();
        initializeTempFiles();
        this.driverId = driverId;
        this.loadBalancing = loadBalancing;
        this.reconnectWhenSplitBrainResolved = reconnectWhenSplitBrainResolved;
        this.defaultScript = defaultScript;
        driverService = new DriverService(this, driverBasePath);
        clusterStarter = clusterStarterBuilder
                .setClusterEvents(driverService.clusterEvents().addAll(clusterEvents))
                .setRouterFunctionBuilder(DriverServerRoutes.getDriverServerRoutes(this, driverService, driverBasePath, routerFunctionBuilder))
                .build();
        driverService.clusterStarter = clusterStarter;
        driverService.driverEvents.addAll(driverEvents);
    }

    public void startWithoutHttpServer() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            clusterStarter.startWithoutHttpServer();
        }
    }

    public void start() throws Throwable {
        if (!isStarted) {
            isStarted = true;
            clusterStarter.start();
        }
    }

    public void start(int serverThreadPoolSize) throws Throwable {
        if (!isStarted) {
            isStarted = true;
            clusterStarter.start(serverThreadPoolSize);
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

    public RouterFunctions.Builder getRouterFunction() {
        return clusterStarter.getRouterFunction();
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

    private void initializeTempFiles() throws IOException {
        var path = Files.createTempDirectory("driver-temp-");
        path.toFile().deleteOnExit();
        var src = PrePy.class.getProtectionDomain().getCodeSource().getLocation();
        var stream = src.openStream();
        var zip = new ZipInputStream(stream);
        var entry = zip.getNextEntry();
        while (entry != null) {
            if (entry.getName().startsWith("Lib/")) {
                if (entry.getName().endsWith(".class") || entry.getName().endsWith(".py")) {
                    var f = new File(path.toUri().getPath() + entry.getName());
                    f.deleteOnExit();
                    FileUtils.copyToFile(zip, f);
                } else {
                    (new File(path.toUri().getPath() + entry.getName())).deleteOnExit();
                }
            }
            entry = zip.getNextEntry();
        }
        zip.close();
        System.setProperty("python.cachedir.skip", "true");
        Py.getSystemState().path.append(new PyString(path.toUri().getPath() + "Lib/"));
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
        var deviceIds = deleteResults.keySet()
                .stream()
                .filter(deviceId ->
                        !driverService.driverProtocols.containsKey(deviceId) &&
                                clusterStarter.getSharedObject().containsKey(deviceId))
                .collect(Collectors.toList());
        if (deviceIds.isEmpty()) return;
        clusterStarter.deleteSharedObject(deviceIds.stream().map(Collections::singletonList).collect(Collectors.toList()));
    }

    public String getDriverId() {
        return driverId;
    }

    public Set<Device> getRegisteredDevices() {
        return driverService.driverProtocols.values().stream().map(protocol -> protocol.device).collect(Collectors.toSet());
    }

    public Map<Integer, Set<String>> getDeviceIdMap() {
        return clusterStarter.getSharedObjectMap().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().keySet()));
    }

    public Map<String, StatusCode> getDeviceStatus() {
        return driverService.getDeviceStatus();
    }
}
