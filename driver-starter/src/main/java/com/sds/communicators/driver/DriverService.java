package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import com.sds.communicators.common.type.Position;
import com.sds.communicators.common.type.StatusCode;
import feign.FeignException;
import feign.Param;
import feign.QueryMap;
import feign.RequestLine;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
class DriverService {
    private final DriverStarter driverStarter;
    private final String driverBasePath;
    private final ReentrantLock mutex = new ReentrantLock();
    private final Object connectAllMutex = new Object();
    final DriverEvents driverEvents = DriverEvents.create();
    final Map<String, DriverProtocol> driverProtocols = new ConcurrentHashMap<>();
    final Map<String, Map<String, Response>> responseMap = new ConcurrentHashMap<>();

    ClusterStarter clusterStarter;

    DriverService(DriverStarter driverStarter, String driverBasePath) {
        this.driverStarter = driverStarter;
        this.driverBasePath = driverBasePath;
    }

    void dispose() {
        mutex.lock();
        try {
            while (!driverProtocols.isEmpty()) {
                clusterStarter.parallelExecute(driverProtocols.keySet(), this::disconnect);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        } finally {
            mutex.unlock();
        }
    }

    /**
     * send response data to output
     *
     * @param responses response list
     */
    void sendResponse(List<Response> responses) throws Exception {
        for (Response response : responses)
            responseMap.compute(response.getDeviceId(), (k, v) -> v == null ? new ConcurrentHashMap<>() : v)
                    .put(response.getTagId(), response);
        driverStarter.sendResponse(responses, driverStarter.getDriverId(), clusterStarter.getNodeIndex());
    }

    /**
     * send device status to output
     *
     * @param deviceStatus device status
     */
    void sendStatus(Status deviceStatus) throws Exception {
        driverStarter.sendStatus(deviceStatus, driverStarter.getDriverId(), clusterStarter.getNodeIndex());
    }

    Map<String, String> connectAllToLeader(int nodeIndex, Set<Device> devices) {
        log.info("try to connect all to leader: {}", UtilFunc.joinDeviceId(devices));
        if (clusterStarter.getPosition() == Position.LEADER) {
            synchronized (connectAllMutex) {
                var ret = new ConcurrentHashMap<String, String>();
                var deviceSet = new HashSet<Device>();
                var deviceIdMap = driverStarter.getDeviceIdMap();
                for (Device device : devices) {
                    var registered = deviceIdMap.entrySet().stream().filter(entry -> entry.getValue().contains(device.getId())).findFirst();
                    if (registered.isPresent()) {
                        log.info("[{}] connect failed, device is already registered in node-index: {}", device.getId(), registered.get().getKey());
                        ret.put(device.getId(), "connect failed, device is already registered in node-index: " + registered.get().getKey());
                    } else if (!device.getId().matches("^[a-zA-Z0-9_]+$")) {
                        log.info("[{}] connect failed, invalid device-id", device.getId());
                        ret.put(device.getId(), "connect failed, invalid device-id");
                    } else {
                        deviceSet.add(device);
                    }
                }
                if (nodeIndex == clusterStarter.getNodeIndex()) {
                    if (!deviceSet.isEmpty())
                        ret.putAll(connectAll(deviceSet));
                } else {
                    var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                                    ret.putAll(clusterStarter.getClient(targetUrl, DriverClientApi.class).connectAllToIndex(driverBasePath, deviceSet)),
                            "connect all to node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(deviceSet));
                    if (res != null)
                        ret.putAll(deviceSet.stream().collect(Collectors.toMap(Device::getId, device -> errorParser(res))));
                }
                return ret;
            }
        } else {
            var ret = new HashMap<String, String>();
            var res = clusterStarter.toLeaderFunc(targetUrl ->
                            ret.putAll(clusterStarter.getClient(targetUrl, DriverClientApi.class).connectAllToLeader(driverBasePath, nodeIndex, devices)),
                    "connect all to leader for node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(devices));
            if (res != null)
                return devices.stream().collect(Collectors.toMap(Device::getId, device -> errorParser(res)));
            return ret;
        }
    }

    private String errorParser(Throwable e) {
        if (e instanceof FeignException && ((FeignException)e).responseBody().isPresent())
            return new String(((FeignException)e).responseBody().get().array());
        else
            return e.getMessage();
    }

    /**
     * connect all device set
     *
     * @param devices device set
     */
    Map<String, String> connectAll(Set<Device> devices) {
        log.info("try to connect all: {}", UtilFunc.joinDeviceId(devices));
        if (mutex.tryLock()) {
            try {
                var ret = new ConcurrentHashMap<String, String>();
                var protocols = new ArrayList<DriverProtocol>();
                var deviceSet = new HashSet<Device>();
                for (Device device : devices) {
                    try {
                        protocols.add(DriverProtocol.build(this, new DriverCommand(driverStarter.defaultScript), device));
                        deviceSet.add(device);
                    } catch (Exception e) {
                        log.error("[{}] connect failed", device.getId(), e);
                        ret.put(device.getId(), "connect failed::" + e.getMessage());
                    }
                }

                var deviceMap = deviceSet.stream().collect(Collectors.toMap(Device::getId, device -> device));
                try {
                    driverStarter.addDevices(deviceMap);
                    clusterStarter.parallelExecute(protocols, protocol -> ret.put(protocol.deviceId, connect(protocol)));
                    return ret;
                } catch (JsonProcessingException e) {
                    log.error("add devices failed, while parsing: {}", deviceMap, e);
                    return deviceSet.stream().collect(Collectors.toMap(Device::getId, device -> "connect failed, while parsing"));
                }
            } finally {
                mutex.unlock();
            }
        } else {
            log.info("add devices failed, device registering process is busy, device-ids: {}", UtilFunc.joinDeviceId(devices));
            return devices.stream().collect(Collectors.toMap(Device::getId, device -> "connect failed, device registering process is busy"));
        }
    }

    /**
     * connect device
     *
     * @param protocol protocol
     */
    private String connect(DriverProtocol protocol) {
        log.trace("[{}] try to connect...", protocol.deviceId);
        driverProtocols.put(protocol.deviceId, protocol);
        var ret = protocol.changeStatus(StatusCode.CONNECTING);
        for (var action : driverEvents.deviceAddedEvents) {
            try {
                action.getValue1().accept(protocol.device);
            } catch (Throwable e) {
                log.error("device added events [{}] failed, device: {}", action.getValue0(), protocol.device, e);
            }
        }
        return Objects.requireNonNullElse(ret, "connected");
    }

    /**
     * disconnect device
     *
     * @param deviceId device id
     */
    private String disconnect(String deviceId) {
        log.trace("[{}] try to disconnect...", deviceId);
        if (!driverProtocols.containsKey(deviceId)) {
            log.info("[{}] disconnect failed, device is not registered", deviceId);
            return "disconnect failed, device is not registered";
        }
        var protocol = driverProtocols.get(deviceId);
        var ret = protocol.changeStatus(StatusCode.DISCONNECTED);
        if (ret == null) {
            for (var action : driverEvents.deviceDeletedEvents) {
                try {
                    action.getValue1().accept(protocol.device);
                } catch (Throwable e) {
                    log.error("device deleted events [{}] failed, device: {}", action.getValue0(), protocol.device, e);
                }
            }
            responseMap.remove(deviceId);
            driverProtocols.remove(deviceId);
            return "disconnected";
        } else {
            return ret;
        }
    }

    Map<String, String> disconnectList(Collection<String> deviceIds, boolean tryLock) {
        log.info("[{}] try to disconnect list", String.join(",", deviceIds));
        if (!tryLock || mutex.tryLock()) {
            if (!tryLock) mutex.lock();
            try {
                var deviceIdMap = driverStarter.getDeviceIdMap();
                var disconnectList = new ArrayList<>();
                for (var entry : deviceIdMap.entrySet()) {
                    var list = deviceIds.stream().filter(deviceId -> entry.getValue().contains(deviceId)).collect(Collectors.toList());
                    if (!list.isEmpty()) {
                        if (entry.getKey() == clusterStarter.getNodeIndex())
                            disconnectList.addAll(list);
                        else
                            disconnectList.add(new Pair<>(entry.getKey(), list));
                    }
                }

                var ret = new ConcurrentHashMap<String, String>();
                clusterStarter.parallelExecute(disconnectList, obj -> {
                    if (obj instanceof String) {
                        ret.put((String) obj, disconnect((String) obj));
                    } else {
                        var nodeIndex = ((Pair<Integer, List<String>>) obj).getValue0();
                        var deviceIdList = ((Pair<Integer, List<String>>) obj).getValue1();
                        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                                        ret.putAll(clusterStarter.getClient(targetUrl, DriverClientApi.class).disconnect(driverBasePath, deviceIdList)),
                                "disconnect to node-index: " + nodeIndex + ", devices: " + String.join(", ", deviceIdList));
                        if (res != null)
                            ret.putAll(deviceIdList.stream().collect(Collectors.toMap(id -> id, id -> errorParser(res))));
                    }
                });
                driverStarter.deleteDevices(ret);
                return ret;
            } finally {
                mutex.unlock();
            }
        } else {
            log.info("[{}] disconnect failed, device registering process is busy", String.join(",", deviceIds));
            return null;
        }
    }

    Map<String, String> disconnectAll(boolean tryLock) {
        log.info("try to disconnect all");
        return disconnectList(driverProtocols.keySet(), tryLock);
    }

    Object executeCommandIdsOnThread(String deviceId, List<String> commandIdList, String initialValue, boolean isResponseOutput) {
        AtomicReference<Object> ret = new AtomicReference<>();
        clusterStarter.syncExecute(() -> ret.set(executeCommandIds(deviceId, commandIdList, initialValue, isResponseOutput)));
        return ret.get();
    }

    Object executeCommandsOnThread(String deviceId, Set<Command> commands, String initialValue, boolean isResponseOutput) {
        AtomicReference<Object> ret = new AtomicReference<>();
        clusterStarter.syncExecute(() -> ret.set(executeCommands(deviceId, commands, initialValue, isResponseOutput)));
        return ret.get();
    }

    Object executeCommandIds(String deviceId, List<String> commandIdList, String initialValue, boolean isResponseOutput) {
        var function = isResponseOutput ? "execute" : "request";
        log.info("[{}] try to " + function + " command-ids({})", deviceId, commandIdList);
        if (driverProtocols.containsKey(deviceId)) {
            try {
                return driverProtocols.get(deviceId).driverCommand.lockedExecuteCommands(commandIdList, initialValue, isResponseOutput);
            } catch (Exception e) {
                var ret =  "[" + deviceId + "] " + function + " command-ids(" + commandIdList + ") failed";
                log.error(ret, e);
                return ret + "::" + e.getMessage();
            }
        } else {
            var ret = "[" + deviceId + "] " + function + " command-ids(" + commandIdList + ") failed, device id not found";
            log.error(ret);
            return ret;
        }
    }

    Object executeCommands(String deviceId, Set<Command> commands, String initialValue, boolean isResponseOutput) {
        var function = isResponseOutput ? "execute" : "request";
        log.info("[{}] try to " + function + " commands({})", deviceId, UtilFunc.joinCommandId(commands));
        if (driverProtocols.containsKey(deviceId)) {
            try {
                return driverProtocols.get(deviceId).driverCommand.lockedExecuteCommands(commands, initialValue, isResponseOutput);
            } catch (Exception e) {
                var ret =  "[" + deviceId + "] " + function + " commands(" + UtilFunc.joinCommandId(commands) + ") failed";
                log.error(ret, e);
                return ret + "::" + e.getMessage();
            }
        } else {
            var ret = "[" + deviceId + "] " + function + " commands(" + UtilFunc.joinCommandId(commands) + ") failed, device id not found";
            log.error(ret);
            return ret;
        }
    }

    /**
     * connect all device set with load balancing option
     *
     * @param devices device set
     */
    Object balancedConnectAll(Set<Device> devices) {
        if (devices.isEmpty()) return new HashMap<>();
        log.info("try to balanced connect all: {}", UtilFunc.joinDeviceId(devices));
        @AllArgsConstructor
        class Size implements Comparable<Size> {
            int index;
            int size;
            @Override
            public int compareTo(Size o) {
                return Integer.compare(size, o.size);
            }
        }
        if (driverStarter.isLoadBalancing()) {
            var dividedList = new HashMap<Integer, Set<Device>>();
            for (var nodeIndex : clusterStarter.getCluster())
                dividedList.put(nodeIndex, new HashSet<>());

            var groupedDevices = new HashMap<String, Set<Device>>();
            var singleDevices = new HashSet<Device>();
            for (Device device : devices) {
                if (!Strings.isNullOrEmpty(device.getGroup()))
                    groupedDevices.compute(device.getGroup(), (k, v) -> v == null ? new HashSet<>() : v)
                            .add(device);
                else
                    singleDevices.add(device);
            }

            var pq = new PriorityQueue<Size>();
            for (var nodeIndex : clusterStarter.getCluster()) {
                pq.add(new Size(nodeIndex,
                        driverStarter.getDeviceIdMap().containsKey(nodeIndex) ?
                                driverStarter.getDeviceIdMap().get(nodeIndex).size() : 0));
            }

            for (var group : groupedDevices.values()) {
                var item = pq.poll();
                if (item != null) {
                    item.size += group.size();
                    pq.add(item);
                    dividedList.get(item.index).addAll(group);
                }
            }

            for (var device : singleDevices) {
                var item = pq.poll();
                if (item != null) {
                    item.size++;
                    pq.add(item);
                    dividedList.get(item.index).add(device);
                }
            }

            log.debug("divided list: {}",
                    dividedList.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                            div -> div.getValue().stream().map(Device::getId).collect(Collectors.toSet())))
            );

            var ret = new ConcurrentHashMap<String, String>();
            clusterStarter.parallelExecute(dividedList.entrySet(), entry -> {
                if (!entry.getValue().isEmpty())
                    ret.putAll(connectAllToLeader(entry.getKey(), entry.getValue()));
            });
            return ret;
        } else {
            return connectAllToLeader(clusterStarter.getNodeIndex(), devices);
        }
    }

    ClusterEvents clusterEvents() {
        return new ClusterEvents()
                .inactivated("disconnect-all", () -> {
                    log.info("node inactivated, disconnect all");
                    disconnectAll(false);
                })
                .clusterDeleted("connect-all for deleted node", (nodeIndex, object) -> {
                    if (object != null) {
                        log.info("node(node-index={}) deleted, connect all deleted node devices", nodeIndex);
                        var deviceSet = new HashSet<Device>();
                        for (Object value : object.values())
                            deviceSet.add(driverStarter.objectMapper.readValue(driverStarter.objectMapper.writeValueAsString(value), Device.class));
                        balancedConnectAll(deviceSet);
                    }
                })
                .overwritten("disconnect duplicated devices", () -> {
                    var deviceIdMap = driverStarter.getDeviceIdMap();
                    for (var entry : deviceIdMap.entrySet()) {
                        if (entry.getKey() < clusterStarter.getNodeIndex()) {
                            var intersection = new HashSet<>(deviceIdMap.get(clusterStarter.getNodeIndex()));
                            intersection.retainAll(entry.getValue());
                            disconnectList(intersection, false);
                        }
                    }
                });
    }

    Map<String, Map<String, Response>> getResponse(int nodeIndex) throws Throwable {
        AtomicReference<Map<String, Map<String, Response>>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getResponse(driverBasePath)),
                "get response map for node-index: " + nodeIndex);
        if (res != null) throw res;
        return ret.get();
    }

    Map<String, Response> getResponse(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<Map<String, Response>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getResponse(driverBasePath, deviceId)),
                "get response for node-index: " + nodeIndex + ", device-id: " + deviceId);
        if (res != null) throw res;
        return ret.get();
    }

    Map<String, StatusCode> getDeviceStatus() {
        return driverProtocols.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getStatus()));
    }

    Map<String, StatusCode> getDeviceStatus(int nodeIndex) throws Throwable {
        AtomicReference<Map<String, StatusCode>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getDeviceStatus(driverBasePath)),
                "get device status map for node-index: " + nodeIndex);
        if (res != null) throw res;
        return ret.get();
    }

    StatusCode getDeviceStatus(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<StatusCode> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getDeviceStatus(driverBasePath, deviceId)), "get device status for node-index: " + nodeIndex + ", device-id: " + deviceId);
        if (res != null) throw res;
        return ret.get();
    }

    Position getPosition() {
        return clusterStarter.getPosition();
    }

    Position getPosition(int nodeIndex) throws Throwable {
        return clusterStarter.getPosition(nodeIndex);
    }

    Set<Integer> getClusterNodes() {
        return clusterStarter.getCluster();
    }

    Map<Integer, Set<String>> getDeviceIdMap() {
        return driverStarter.getDeviceIdMap();
    }

    List<Response> executeCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).executeCommands(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commands)),
                "execute commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (res != null) throw res;
        return ret.get();
    }

    List<Response> requestCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).requestCommands(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commands)),
                "request commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (res != null) throw res;
        return ret.get();
    }

    List<Response> executeCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).executeCommandIds(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commandIdList)),
                "execute command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (res != null) throw res;
        return ret.get();
    }

    List<Response> requestCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var res = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).requestCommandIds(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commandIdList)),
                "request command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (res != null) throw res;
        return ret.get();
    }

    private interface DriverClientApi {
        @RequestLine("POST {driverBasePath}/connect-all-to-index")
        Map<String, String> connectAllToIndex(@Param("driverBasePath") String driverBasePath, Set<Device> devices);

        @RequestLine("POST {driverBasePath}/connect-all-to-leader/{nodeIndex}")
        Map<String, String> connectAllToLeader(@Param("driverBasePath") String driverBasePath, @Param("nodeIndex") int nodeIndex, Set<Device> devices);

        @RequestLine("DELETE {driverBasePath}/disconnect")
        Map<String, String> disconnect(@Param("driverBasePath") String driverBasePath, List<String> deviceIds);

        @RequestLine("GET {driverBasePath}/device-status/{id}")
        StatusCode getDeviceStatus(@Param("driverBasePath") String driverBasePath, @Param("id") String id);

        @RequestLine("GET {driverBasePath}/device-status")
        Map<String, StatusCode> getDeviceStatus(@Param("driverBasePath") String driverBasePath);

        @RequestLine("GET {driverBasePath}/response/{deviceId}")
        Map<String, Response> getResponse(@Param("driverBasePath") String driverBasePath, @Param("deviceId") String deviceId);

        @RequestLine("GET {driverBasePath}/response")
        Map<String, Map<String, Response>> getResponse(@Param("driverBasePath") String driverBasePath);

        @RequestLine("POST {driverBasePath}/execute-commands/{deviceId}")
        List<Response> executeCommands(@Param("driverBasePath") String driverBasePath, @Param("deviceId") String deviceId, @QueryMap Map<String, String> param, Set<Command> commands);

        @RequestLine("POST {driverBasePath}/request-commands/{deviceId}")
        List<Response> requestCommands(@Param("driverBasePath") String driverBasePath, @Param("deviceId") String deviceId, @QueryMap Map<String, String> param, Set<Command> commands);

        @RequestLine("POST {driverBasePath}/execute-command-ids/{deviceId}")
        List<Response> executeCommandIds(@Param("driverBasePath") String driverBasePath, @Param("deviceId") String deviceId, @QueryMap Map<String, String> param, List<String> commandIdList);

        @RequestLine("POST {driverBasePath}/request-command-ids/{deviceId}")
        List<Response> requestCommandIds(@Param("driverBasePath") String driverBasePath, @Param("deviceId") String deviceId, @QueryMap Map<String, String> param, List<String> commandIdList);
    }
}
