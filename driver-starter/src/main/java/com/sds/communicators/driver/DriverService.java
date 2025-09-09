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
import org.python.core.PyByteArray;
import org.python.core.PyFunction;
import org.python.core.PyObject;
import org.python.util.PythonInterpreter;

import java.nio.charset.StandardCharsets;
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
    private final PyFunction jsonLoads;
    final DriverEvents driverEvents = DriverEvents.create();
    final Map<String, DriverProtocol> driverProtocols = new ConcurrentHashMap<>();
    final Map<String, Map<String, Response>> responseMap = new ConcurrentHashMap<>();

    ClusterStarter clusterStarter;

    DriverService(DriverStarter driverStarter, String driverBasePath) throws Exception{
        this.driverStarter = driverStarter;
        this.driverBasePath = driverBasePath;

        var py = new PythonInterpreter();
        py.exec("from json import loads as json_loads");
        jsonLoads = (PyFunction) py.get("json_loads");
        if (jsonLoads == null) throw new Exception("json loads function is not loaded");
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

    PyObject stringToPyObject(String s) {
        return bytesToPyObject(s.getBytes(StandardCharsets.UTF_8));
    }

    PyObject bytesToPyObject(byte[] bytes) {
        var byteArray = new PyByteArray(bytes);
        var unicode = byteArray.decode("utf-8");
        try {
            return jsonLoads.__call__(unicode);
        } catch (Exception e) {
            return unicode;
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
                    var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                                    ret.putAll(clusterStarter.getClient(targetUrl, DriverClientApi.class).connectAllToIndex(driverBasePath, deviceSet)),
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
                            ret.putAll(clusterStarter.getClient(targetUrl, DriverClientApi.class).connectAllToLeader(driverBasePath, nodeIndex, devices)),
                    "connect all to leader for node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(devices));
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
        if (devices.isEmpty())
            return new HashMap<>();
        synchronized (driverMutex) {
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
        DriverEvents.fireEvents(driverEvents.deviceAddedEvents, protocol.device, "device(" + protocol.device + ") added");
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
                                    ret.putAll(clusterStarter.getClient(targetUrl, DriverClientApi.class).disconnect(driverBasePath, deviceIdList)),
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
                    var protocol = DriverProtocol.build(this, new DriverCommand(driverStarter.defaultScript), entry.getValue().device);
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
                return driverProtocols.get(deviceId).driverCommand.lockedExecuteCommands(commandIdList, stringToPyObject(initialValue), isResponseOutput);
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
                return driverProtocols.get(deviceId).driverCommand.lockedExecuteCommands(commands, stringToPyObject(initialValue), isResponseOutput);
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
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getResponse(driverBasePath)),
                "get response map for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    Map<String, Response> getResponse(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<Map<String, Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getResponse(driverBasePath, deviceId)),
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
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getDeviceStatus(driverBasePath)),
                "get device status map for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    StatusCode getDeviceStatus(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<StatusCode> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).getDeviceStatus(driverBasePath, deviceId)), "get device status for node-index: " + nodeIndex + ", device-id: " + deviceId);
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
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).executeCommands(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commands)),
                "execute commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> requestCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).requestCommands(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commands)),
                "request commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> executeCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).executeCommandIds(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commandIdList)),
                "execute command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> requestCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(clusterStarter.getClient(targetUrl, DriverClientApi.class).requestCommandIds(driverBasePath,
                                deviceId,
                                ImmutableMap.of("initial-value", initialValue),
                                commandIdList)),
                "request command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (result != null) throw result;
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
