package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.google.common.base.Strings;
import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import com.sds.communicators.common.type.Position;
import com.sds.communicators.common.type.StatusCode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
class DriverService {
    private final DriverStarter driverStarter;
    private final String driverBasePath;
    private final Object driverMutex = new Object();
    private final Object connectAllMutex = new Object();
    final DriverEvents driverEvents = DriverEvents.create();
    final Map<String, DriverProtocol> driverProtocols = new ConcurrentHashMap<>();
    final Map<String, Map<String, Response>> responseMap = new ConcurrentHashMap<>();

    ClusterStarter clusterStarter;

    DriverService(DriverStarter driverStarter, String driverBasePath) throws Exception{
        this.driverStarter = driverStarter;
        this.driverBasePath = driverBasePath;
    }

    void dispose() {
        synchronized (driverMutex) {
            while (!driverProtocols.isEmpty()) {
                var threads = new ArrayList<Thread>();
                driverProtocols.keySet().forEach(deviceId ->
                        threads.add(new Thread(() -> disconnect(deviceId))));
                threads.forEach(Thread::start);
                threads.forEach(th -> {
                    try {
                        th.join();
                    } catch (InterruptedException ignored) {}
                });
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    void sendResponse(List<Response> responses) throws Exception {
        for (Response response : responses)
            responseMap.compute(response.getDeviceId(), (k, v) -> v == null ? new ConcurrentHashMap<>() : v)
                    .put(response.getTagId(), response);
        driverStarter.sendResponse(responses, driverStarter.getDriverId(), clusterStarter.getNodeIndex());
    }

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
                    var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                                    ret.putAll(callNode(targetUrl, DriverServerRoutes.INTERNAL_PATH + "/connect-all-to-index",
                                            "POST", deviceSet, mapOfString(), Map.of())),
                            "connect all to node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(deviceSet));
                    if (result != null) {
                        log.error("connect all to node-index: {} failed, connect to leader, devices: {}", nodeIndex, UtilFunc.joinDeviceId(deviceSet), result);
                        ret.putAll(connectAll(deviceSet));
                    }
                }
                return ret;
            }
        } else {
            var ret = new HashMap<String, String>();
            clusterStarter.toLeaderFuncConfirmed(targetUrl ->
                            ret.putAll(callNode(targetUrl, DriverServerRoutes.INTERNAL_PATH + "/connect-all-to-leader/" + nodeIndex,
                                    "POST", devices, mapOfString(), Map.of())),
                    "connect all to leader for node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(devices));
            return ret;
        }
    }

    /** NodeHttpClient already puts the failed response body into the message */
    private String errorParser(Throwable e) {
        return e.getMessage();
    }

    Map<String, String> connectAll(Set<Device> devices) {
        log.info("try to connect all: {}", UtilFunc.joinDeviceId(devices));
        if (devices.isEmpty())
            return new HashMap<>();
        synchronized (driverMutex) {
            var ret = new ConcurrentHashMap<String, String>();
            var protocols = new ArrayList<DriverProtocol>();
            var deviceSet = new HashSet<Device>();
            for (Device device : devices) {
                try {
                    protocols.add(DriverProtocol.build(this, driverStarter.defaultScript, device));
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
        }
    }

    private String connect(DriverProtocol protocol) {
        log.trace("[{}] try to connect...", protocol.deviceId);
        driverProtocols.put(protocol.deviceId, protocol);
        var ret = protocol.changeStatus(StatusCode.CONNECTING);
        DriverEvents.fireEvents(driverEvents.deviceAddedEvents, protocol.device, "device(" + protocol.device + ") added");
        return Objects.requireNonNullElse(ret, "connected");
    }

    private String disconnect(String deviceId) {
        log.trace("[{}] try to disconnect...", deviceId);
        if (!driverProtocols.containsKey(deviceId)) {
            log.info("[{}] disconnect failed, device is not registered", deviceId);
            return "disconnect failed, device is not registered";
        }
        var protocol = driverProtocols.get(deviceId);
        var ret = protocol.changeStatus(StatusCode.DISCONNECTED);
        if (ret == null) {
            DriverEvents.fireEvents(driverEvents.deviceDeletedEvents, protocol.device, "device(" + protocol.device + ") deleted");
            responseMap.remove(deviceId);
            driverProtocols.remove(deviceId);
            return "disconnected";
        } else {
            return ret;
        }
    }

    Map<String, String> disconnectList(Collection<String> deviceIds, boolean isSelfDevices) {
        log.info("[{}] try to disconnect list", String.join(",", deviceIds));
        if (deviceIds.isEmpty())
            return new HashMap<>();
        synchronized (driverMutex) {
            var deviceIdMap = driverStarter.getDeviceIdMap();
            var disconnectList = new ArrayList<>();
            for (var entry : deviceIdMap.entrySet()) {
                var list = deviceIds.stream().filter(deviceId -> entry.getValue().contains(deviceId)).collect(Collectors.toList());
                if (!list.isEmpty()) {
                    if (entry.getKey() == clusterStarter.getNodeIndex()) {
                        disconnectList.addAll(list);
                    } else {
                        if (!isSelfDevices)
                            disconnectList.add(new Pair<>(entry.getKey(), list));
                    }
                }
            }

            var ret = new ConcurrentHashMap<String, String>();
            clusterStarter.parallelExecute(disconnectList, obj -> {
                if (obj instanceof String) {
                    ret.put((String) obj, disconnect((String) obj));
                } else {
                    var nodeIndex = ((Pair<Integer, List<String>>) obj).getValue0();
                    var deviceIdList = ((Pair<Integer, List<String>>) obj).getValue1();
                    var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                                    ret.putAll(callNode(targetUrl, "/disconnect", "DELETE", deviceIdList,
                                            http().type(new TypeReference<Map<String, String>>() {}), Map.of())),
                            "disconnect to node-index: " + nodeIndex + ", devices: " + String.join(", ", deviceIdList));
                    if (result != null)
                        ret.putAll(deviceIdList.stream().collect(Collectors.toMap(id -> id, id -> errorParser(result))));
                }
            });
            driverStarter.deleteDevices(ret);
            return ret;
        }
    }

    Map<String, String> disconnectAll() {
        log.info("try to disconnect all");
        return disconnectList(driverProtocols.keySet(), true);
    }

    Map<String, String> reconnectAll() {
        log.info("try to reconnect all");
        synchronized (driverMutex) {
            var ret = new ConcurrentHashMap<String, String>();
            clusterStarter.parallelExecute(driverProtocols.entrySet(), entry -> {
                var result = entry.getValue().changeStatus(StatusCode.DISCONNECTED);
                if (result == null) {
                    var protocol = DriverProtocol.build(this, driverStarter.defaultScript, entry.getValue().device);
                    driverProtocols.put(entry.getKey(), protocol);
                    ret.put(entry.getKey(), Objects.requireNonNullElse(protocol.changeStatus(StatusCode.CONNECTING), "connected"));
                } else {
                    ret.put(entry.getKey(), result);
                }
            });
            return ret;
        }
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
        if (driverStarter.loadBalancing) {
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
                    disconnectAll();
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
                .overwritten("check duplicated devices", nodeIndex -> {
                    var deviceIdMap = driverStarter.getDeviceIdMap();
                    for (var entry : deviceIdMap.entrySet()) {
                        var intersection = new HashSet<>(driverProtocols.keySet());
                        intersection.retainAll(entry.getValue());
                        if (!intersection.isEmpty()) {
                            log.info("disconnect duplicated devices: {}", String.join(", ", intersection));
                            var reconnectList = driverProtocols.entrySet().stream()
                                    .filter(kv -> intersection.contains(kv.getKey()))
                                    .collect(Collectors.toMap(Map.Entry::getKey, kv -> kv.getValue().device));
                            disconnectList(reconnectList.keySet(), true);
                            balancedConnectAll(new HashSet<>(reconnectList.values()));
                        }
                    }
                });
    }

    Map<String, Map<String, Response>> getResponse(int nodeIndex) throws Throwable {
        AtomicReference<Map<String, Map<String, Response>>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/response", "GET", null,
                                http().type(new TypeReference<Map<String, Map<String, Response>>>() {}), Map.of())),
                "get response map for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    Map<String, Response> getResponse(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<Map<String, Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/response/" + deviceId, "GET", null,
                                http().type(new TypeReference<Map<String, Response>>() {}), Map.of())),
                "get response for node-index: " + nodeIndex + ", device-id: " + deviceId);
        if (result != null) throw result;
        return ret.get();
    }

    Map<String, StatusCode> getDeviceStatus() {
        return driverProtocols.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getStatus()));
    }

    Map<String, StatusCode> getDeviceStatus(int nodeIndex) throws Throwable {
        AtomicReference<Map<String, StatusCode>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/device-status", "GET", null,
                                http().type(new TypeReference<Map<String, StatusCode>>() {}), Map.of())),
                "get device status map for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    StatusCode getDeviceStatus(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<StatusCode> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                ret.set(callNode(targetUrl, "/device-status/" + deviceId, "GET", null,
                        http().type(StatusCode.class), Map.of())), "get device status for node-index: " + nodeIndex + ", device-id: " + deviceId);
        if (result != null) throw result;
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
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/execute-commands/" + deviceId, "POST", commands,
                                listOfResponse(), initialValueHeader(initialValue))),
                "execute commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> requestCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/request-commands/" + deviceId, "POST", commands,
                                listOfResponse(), initialValueHeader(initialValue))),
                "request commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> executeCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/execute-command-ids/" + deviceId, "POST", commandIdList,
                                listOfResponse(), initialValueHeader(initialValue))),
                "execute command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> requestCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "/request-command-ids/" + deviceId, "POST", commandIdList,
                                listOfResponse(), initialValueHeader(initialValue))),
                "request command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (result != null) throw result;
        return ret.get();
    }

    /**
     * node-to-node call over the cluster's shared HTTP client, so driver traffic reuses
     * the same connection pool per peer as the cluster's own internal calls.
     */
    private <T> T callNode(String targetUrl, String path, String method, Object body,
                           JavaType responseType, Map<String, String> headers) {
        return http().call(targetUrl + driverBasePath + path, method, body, responseType, headers);
    }

    private NodeHttpClient http() {
        return clusterStarter.getNodeHttpClient();
    }

    private JavaType listOfResponse() {
        return http().type(new TypeReference<List<Response>>() {});
    }

    private JavaType mapOfString() {
        return http().type(new TypeReference<Map<String, String>>() {});
    }

    /**
     * The routes read {@code initial-value} from a request header, so it must be sent as one.
     * A null value is simply omitted, which the route reads back as null.
     */
    private Map<String, String> initialValueHeader(String initialValue) {
        return initialValue == null ? Map.of() : Map.of("initial-value", initialValue);
    }
}
