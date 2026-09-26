package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
class DriverService {
    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType MAP_OF_STRING = TYPES.constructType(new TypeReference<Map<String, String>>() {});
    private static final JavaType LIST_OF_RESPONSE = TYPES.constructType(new TypeReference<List<Response>>() {});
    private static final JavaType RESPONSE_MAPS = TYPES.constructType(new TypeReference<Map<String, Map<String, Response>>>() {});
    private static final JavaType RESPONSE_MAP = TYPES.constructType(new TypeReference<Map<String, Response>>() {});
    private static final JavaType STATUS_MAP = TYPES.constructType(new TypeReference<Map<String, StatusCode>>() {});
    private static final JavaType STATUS_CODE = TYPES.constructType(StatusCode.class);
    private static final String NOT_ACTIVATED = "connect failed, cluster is not activated (quorum not reached)";
    /** heartbeat intervals between two runs of the periodic duplicate check */
    private static final int DUPLICATE_CHECK_HEARTBEATS = 5;

    private final DriverStarter driverStarter;
    private final String driverBasePath;
    private final Object driverMutex = new Object();
    private final Object connectAllMutex = new Object();
    final DriverEvents driverEvents = DriverEvents.create();
    final Map<String, DriverProtocol> driverProtocols = new ConcurrentHashMap<>();
    final Map<String, Map<String, Response>> responseMap = new ConcurrentHashMap<>();
    /** deviceId -> current setting (script data included) of the devices disconnected on inactivation, reconnected on the next activation (guarded by itself) */
    private final Map<String, Device> inactivationDropped = new HashMap<>();
    /** deviceIds whose protocol connectAll is building: module-level protocolScript code may write data before addDevices registers them */
    final Set<String> building = Collections.synchronizedSet(new HashSet<>());
    /** deviceIds whose protocol connectAll is connecting: the duplicate check leaves them to a later run */
    final Set<String> connecting = Collections.synchronizedSet(new HashSet<>());
    /** the periodic duplicate check, running from start until dispose */
    private volatile Disposable duplicateCheck = Disposable.disposed();
    /** the duplicate checks scheduled after a change of membership or leadership, until they have run; cancelled on dispose */
    private final CompositeDisposable delayedDuplicateChecks = new CompositeDisposable();
    /** guards duplicateCheckRunning and duplicateCheckRerun: one duplicate check runs at a time */
    private final Object duplicateCheckMutex = new Object();
    private boolean duplicateCheckRunning = false;
    /** requested while a check was running: that check runs exactly once more afterwards */
    private boolean duplicateCheckRerun = false;
    /**
     * per lower node-index that did not confirm its duplicates within the probe timeout: asked on a task of its own
     * instead of by the check, until it answers within the probe timeout again (guarded by itself)
     */
    private final Map<Integer, SlowConfirmation> slowConfirmations = new HashMap<>();
    /** the confirmations and probes running on a task of their own, until they have run; cancelled on dispose */
    private final CompositeDisposable slowConfirmationTasks = new CompositeDisposable();

    ClusterStarter clusterStarter;
    /**
     * the cluster's probe timeout, set with clusterStarter: bounds the device-status call that confirms a peer runs a
     * device, so one slow peer does not hold up the duplicate check or a failover for the full read timeout
     */
    volatile Duration probeTimeout;
    /** the cluster's read timeout, set with clusterStarter: the longest a lower node that is slow to confirm is waited for */
    volatile Duration readTimeout;

    DriverService(DriverStarter driverStarter, String driverBasePath) throws Exception{
        this.driverStarter = driverStarter;
        this.driverBasePath = driverBasePath;
    }

    /**
     * Starts the periodic duplicate check against every member. The event-driven checks miss a peer's replica that
     * gains a device through a delta, and a check that ran while the other copy was still connecting
     */
    void start() {
        long period = DUPLICATE_CHECK_HEARTBEATS * (long) clusterStarter.getHeartbeatSendingIntervalMillis();
        duplicateCheck.dispose();
        duplicateCheck = Observable.interval(period, period, TimeUnit.MILLISECONDS, Schedulers.io())
                .subscribe(tick -> checkDuplicatedDevices());
    }

    void dispose() {
        duplicateCheck.dispose();
        delayedDuplicateChecks.clear();
        slowConfirmationTasks.clear();
        synchronized (slowConfirmations) {
            slowConfirmations.clear();
        }
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
        // a node below quorum may be the minority side of a partition, where the majority still runs these devices
        if (!clusterStarter.isActivated())
            return refuseNotActivated(devices);
        if (clusterStarter.getPosition() == Position.LEADER) {
            synchronized (connectAllMutex) {
                // quorum may have been lost while waiting for the mutex
                if (!clusterStarter.isActivated())
                    return refuseNotActivated(devices);
                // re-check: after a healed split brain the other former leader may have held the mutex
                // while this node stepped down, and acting as leader here would connect the devices twice
                if (clusterStarter.getPosition() == Position.LEADER) {
                    var ret = new ConcurrentHashMap<String, String>();
                    var deviceSet = new HashSet<Device>();
                    var deviceIdMap = driverStarter.getDeviceIdMap();
                    for (Device device : devices) {
                        if (device.getId() == null || !device.getId().matches("^[a-zA-Z0-9_]+$")) {
                            log.info("[{}] connect failed, invalid device-id", device.getId());
                            ret.put(String.valueOf(device.getId()), "connect failed, invalid device-id");
                            continue;
                        }
                        var registered = deviceIdMap.entrySet().stream().filter(entry -> entry.getValue().contains(device.getId())).findFirst();
                        if (registered.isPresent()) {
                            log.info("[{}] connect failed, device is already registered in node-index: {}", device.getId(), registered.get().getKey());
                            ret.put(device.getId(), "connect failed, device is already registered in node-index: " + registered.get().getKey());
                        } else {
                            deviceSet.add(device);
                        }
                    }
                    if (nodeIndex == clusterStarter.getNodeIndex()) {
                        if (!deviceSet.isEmpty())
                            ret.putAll(connectAll(deviceSet));
                    } else {
                        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                                        ret.putAll(callNode(targetUrl, "POST", DriverServerRoutes.INTERNAL_PATH + "/connect-all-to-index",
                                                deviceSet, MAP_OF_STRING)),
                                "connect all to node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(deviceSet));
                        if (result != null) {
                            // the target may be unreachable because this node was partitioned away meanwhile
                            if (!clusterStarter.isActivated())
                                return refuseNotActivated(devices);
                            log.error("connect all to node-index: {} failed, connect to leader, devices: {}", nodeIndex, UtilFunc.joinDeviceId(deviceSet), result);
                            ret.putAll(connectAll(deviceSet));
                        }
                    }
                    return ret;
                }
            }
            log.info("no longer leader, forward connect all to leader: {}", UtilFunc.joinDeviceId(devices));
        }
        var ret = new HashMap<String, String>();
        clusterStarter.toLeaderFuncConfirmed(targetUrl ->
                        ret.putAll(callNode(targetUrl, "POST", DriverServerRoutes.INTERNAL_PATH + "/connect-all-to-leader/" + nodeIndex,
                                devices, MAP_OF_STRING)),
                "connect all to leader for node-index: " + nodeIndex + ", devices: " + UtilFunc.joinDeviceId(devices));
        return ret;
    }

    private Map<String, String> refuseNotActivated(Set<Device> devices) {
        log.warn("connect all to leader refused, cluster is not activated (quorum not reached): {}", UtilFunc.joinDeviceId(devices));
        var ret = new HashMap<String, String>();
        for (Device device : devices)
            ret.put(String.valueOf(device.getId()), NOT_ACTIVATED);
        return ret;
    }

    /** surfaced through the public API, so use the reason (peer's response body) without internal node URLs */
    private String errorParser(Throwable e) {
        return e instanceof NodeHttpClient.CallException ce ? ce.getReason() : e.getMessage();
    }

    Map<String, String> connectAll(Set<Device> devices) {
        log.info("try to connect all: {}", UtilFunc.joinDeviceId(devices));
        if (devices.isEmpty())
            return new HashMap<>();
        synchronized (driverMutex) {
            var ret = new ConcurrentHashMap<String, String>();
            var protocols = new ArrayList<DriverProtocol>();
            var deviceSet = new HashSet<Device>();
            var failed = new HashMap<String, String>();
            try {
                for (Device device : devices) {
                    // the leader's view of this node may be stale; replacing the running protocol would orphan it
                    if (device.getId() != null && driverProtocols.containsKey(device.getId())) {
                        log.info("[{}] connect failed, device is already connected on this node", device.getId());
                        ret.put(device.getId(), "connect failed, device is already connected on this node");
                        continue;
                    }
                    try {
                        building.add(device.getId());
                        protocols.add(DriverProtocol.build(this, driverStarter.defaultScript, device));
                        deviceSet.add(device);
                    } catch (Exception e) {
                        log.error("[{}] connect failed", device.getId(), e);
                        var result = "connect failed::" + e.getMessage();
                        ret.put(device.getId(), result);
                        failed.put(device.getId(), result);
                    }
                }

                // registered after the build, so the configured (on failover: the carried) data is merged over what
                // the protocolScript wrote, as the defaults
                var deviceMap = deviceSet.stream().collect(Collectors.toMap(Device::getId, device -> device));
                try {
                    driverStarter.addDevices(deviceMap);
                } catch (JsonProcessingException e) {
                    log.error("add devices failed, while parsing: {}", deviceMap, e);
                    deviceMap.keySet().forEach(deviceId -> failed.put(deviceId, "connect failed, while parsing"));
                    return deviceSet.stream().collect(Collectors.toMap(Device::getId, device -> "connect failed, while parsing"));
                }
            } finally {
                building.clear();
                // data written by the protocolScript of a device that did not get registered
                if (!failed.isEmpty())
                    driverStarter.deleteDevices(failed);
            }
            // left to a later duplicate check until connected (while building, a device is not in driverProtocols yet)
            protocols.forEach(protocol -> connecting.add(protocol.deviceId));
            try {
                clusterStarter.parallelExecute(protocols, protocol -> ret.put(protocol.deviceId, connect(protocol)));
            } finally {
                connecting.clear();
            }
            return ret;
        }
    }

    private String connect(DriverProtocol protocol) {
        log.trace("[{}] try to connect...", protocol.deviceId);
        if (driverProtocols.putIfAbsent(protocol.deviceId, protocol) != null) {
            log.info("[{}] connect failed, device is already connected on this node", protocol.deviceId);
            return "connect failed, device is already connected on this node";
        }
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
                                    ret.putAll(callNode(targetUrl, "DELETE", "/disconnect", deviceIdList, MAP_OF_STRING)),
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
            // a snapshot: membership changes while this runs (a failover runs exactly then)
            var cluster = new HashSet<>(clusterStarter.getCluster());
            var dividedList = new HashMap<Integer, Set<Device>>();
            for (var nodeIndex : cluster)
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
            var deviceIdMap = driverStarter.getDeviceIdMap();
            for (var nodeIndex : cluster) {
                pq.add(new Size(nodeIndex,
                        deviceIdMap.containsKey(nodeIndex) ?
                                deviceIdMap.get(nodeIndex).size() : 0));
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
                    // a stall (GC pause) or a short partition inactivates only until the queued heartbeats arrive
                    Thread.sleep(2L * clusterStarter.getHeartbeatSendingIntervalMillis());
                    // held while disconnecting, so the activated handler does not take the list before it is complete
                    synchronized (inactivationDropped) {
                        if (clusterStarter.isActivated()) {
                            log.info("node inactivated, but activated again meanwhile, keep devices");
                            return;
                        }
                        log.info("node inactivated, disconnect all");
                        // the list is taken and disconnected under driverMutex, so no device is connected in between
                        synchronized (driverMutex) {
                            var dropped = currentSettings(driverProtocols.values());
                            disconnectList(dropped.keySet(), true);
                            // remembered only if dropped: a device that is still connected here keeps running
                            dropped.keySet().removeIf(driverProtocols::containsKey);
                            inactivationDropped.putAll(dropped);
                        }
                    }
                })
                .activated("reconnect devices dropped on inactivation", () -> {
                    Thread.sleep(2L * clusterStarter.getHeartbeatSendingIntervalMillis());
                    Set<Device> dropped;
                    synchronized (inactivationDropped) {
                        if (!clusterStarter.isActivated() || inactivationDropped.isEmpty())
                            return;
                        // the majority may have failed them over meanwhile
                        var registered = getDeviceIdMap().values().stream().flatMap(Set::stream).collect(Collectors.toSet());
                        dropped = inactivationDropped.values().stream()
                                .filter(device -> !registered.contains(device.getId()))
                                .collect(Collectors.toSet());
                        inactivationDropped.clear();
                    }
                    if (dropped.isEmpty())
                        return;
                    log.info("node activated, reconnect devices dropped on inactivation: {}", UtilFunc.joinDeviceId(dropped));
                    Object result = null;
                    try {
                        result = balancedConnectAll(dropped);
                        log.info("reconnect devices dropped on inactivation, result: {}", result);
                    } finally {
                        // retried on the next activation: refused because inactivated again meanwhile, or without any
                        // result because its connect threw (or parallelExecute swallowed that)
                        var results = result instanceof Map<?, ?> map ? map : Map.of();
                        synchronized (inactivationDropped) {
                            for (var device : dropped) {
                                var deviceResult = results.get(device.getId());
                                if (deviceResult == null || NOT_ACTIVATED.equals(deviceResult))
                                    inactivationDropped.putIfAbsent(device.getId(), device);
                            }
                        }
                    }
                })
                .clusterDeleted("connect-all for deleted node", (nodeIndex, object) -> {
                    if (object != null) {
                        // in a partition the per-node lost timers fire up to a heartbeat interval apart, so let the
                        // membership settle before deciding whether this side still has quorum. Longer than the lost
                        // node's inactivation settle (2 intervals) plus that skew, so a partitioned node has started to
                        // disconnect its devices before they are connected here
                        Thread.sleep(4L * clusterStarter.getHeartbeatSendingIntervalMillis());
                        // below quorum this node may be the partitioned one, while the "deleted" node still runs its devices
                        if (!clusterStarter.isActivated()) {
                            log.warn("node(node-index={}) deleted, but cluster is not activated (quorum not reached), skip connecting its devices", nodeIndex);
                            return;
                        }
                        log.info("node(node-index={}) deleted, connect all deleted node devices", nodeIndex);
                        var deviceSet = new HashSet<Device>();
                        for (var entry : object.entrySet()) {
                            var device = failoverDevice(nodeIndex, entry.getKey(), entry.getValue());
                            if (device != null)
                                deviceSet.add(device);
                        }
                        // the partition may have healed meanwhile, with the node still running its devices
                        skipDevicesRunningOnMember(nodeIndex, deviceSet);
                        var result = balancedConnectAll(deviceSet);
                        log.info("node(node-index={}) deleted, connect all deleted node devices, result: {}", nodeIndex, result);
                    }
                })
                .overwritten("check duplicated devices", nodeIndex -> checkDuplicatedDevices())
                // a replica overwritten before its node (re)joined was skipped by the overwritten check. The settle lets
                // a restarted node's new object replace the replica still held from its previous run
                .clusterAdded("check duplicated devices", nodeIndex -> checkDuplicatedDevicesLater())
                // transient dual leaders may both have failed the same devices over. The settle lets the replicas
                // sync with the remaining leader first; the periodic check catches what is still connecting
                .becomeFollower("check duplicated devices", this::checkDuplicatedDevicesLater)
                .splitBrainResolved("check duplicated devices", this::checkDuplicatedDevicesLater);
    }

    /**
     * The duplicate check against every member, for every trigger: periodic, a replaced replica, a change of membership
     * or leadership. One runs at a time; a request while one runs makes that one run exactly once more afterwards, as
     * it may have read the replicas before the change behind the request. Never throws: that would end the periodic check
     */
    void checkDuplicatedDevices() {
        synchronized (duplicateCheckMutex) {
            if (duplicateCheckRunning) {
                duplicateCheckRerun = true;
                return;
            }
            duplicateCheckRunning = true;
        }
        var again = true;
        while (again) {
            try {
                checkDuplicatedDevices(clusterStarter.getCluster());
            } catch (Throwable e) {
                try {
                    log.error("check duplicated devices failed", e);
                } catch (Throwable ignored) {
                }
            }
            synchronized (duplicateCheckMutex) {
                again = duplicateCheckRerun;
                duplicateCheckRerun = false;
                duplicateCheckRunning = again;
            }
        }
    }

    /**
     * The duplicate check once the replicas had two heartbeat intervals to settle. Scheduled instead of slept on the
     * event's thread, so the handler returns at once
     */
    private void checkDuplicatedDevicesLater() {
        Completable.timer(2L * clusterStarter.getHeartbeatSendingIntervalMillis(), TimeUnit.MILLISECONDS, Schedulers.computation())
                // the check calls a peer and waits for driverMutex, which does not belong on a computation thread
                .observeOn(Schedulers.io())
                .subscribe(this::checkDuplicatedDevices, e -> log.error("delayed duplicate check failed", e), delayedDuplicateChecks);
    }

    /**
     * A device registered on this node and on a member stays on the lower node-index. Both nodes see each other's
     * object, so only the higher one yields; the device keeps running on the lower one, hence no reconnect here
     */
    private void checkDuplicatedDevices(Collection<Integer> nodeIndexes) {
        if (driverProtocols.isEmpty()) {
            forgetSlowConfirmations(Set.of());
            return;
        }
        var deviceIdMap = driverStarter.getDeviceIdMap();
        var members = clusterStarter.getCluster();
        var lowerNodes = new TreeMap<Integer, Set<String>>();
        for (int nodeIndex : new TreeSet<>(nodeIndexes)) {
            // this node's own object always holds its own devices
            if (nodeIndex == clusterStarter.getNodeIndex())
                continue;
            // a replica kept for a removed node only waits for the leader's failover, which may have moved its devices here
            if (!members.contains(nodeIndex))
                continue;
            var peerDeviceIds = deviceIdMap.get(nodeIndex);
            if (peerDeviceIds == null)
                continue;
            var intersection = new TreeSet<>(driverProtocols.keySet());
            intersection.retainAll(peerDeviceIds);
            // a device still connecting here (e.g. a failover) is checked again once connected
            intersection.removeIf(deviceId -> building.contains(deviceId) || connecting.contains(deviceId));
            if (intersection.isEmpty())
                continue;
            if (nodeIndex < clusterStarter.getNodeIndex())
                lowerNodes.put(nodeIndex, intersection);
            else
                log.info("devices duplicated with node-index: {}, keep devices (that node yields): {}", nodeIndex, String.join(", ", intersection));
        }
        forgetSlowConfirmations(lowerNodes.keySet());
        // each lower node confirms on its own task, bounded by the probe timeout, so a slow one does not hold back the
        // yields to the others
        clusterStarter.parallelExecute(lowerNodes.entrySet(), entry -> {
            try {
                yieldDuplicatedDevices(entry.getKey(), entry.getValue());
            } catch (Throwable e) {
                log.error("yield devices duplicated with node-index: {} failed", entry.getKey(), e);
            }
        });
    }

    /**
     * Disconnects the devices that the lower nodeIndex runs as well. Only those it confirms: its replica here may be
     * stale (e.g. still held from before it rejoined), and yielding a device it no longer runs would leave it nowhere.
     * A node that did not confirm within the probe timeout is asked on a task of its own (see {@link #confirmOnItsOwn}),
     * meanwhile probed by each run (see {@link #probeOnItsOwn}), and the answer is used here by the next run
     */
    private void yieldDuplicatedDevices(int nodeIndex, Set<String> deviceIds) {
        Map<String, StatusCode> running = null;
        synchronized (slowConfirmations) {
            var slow = slowConfirmations.get(nodeIndex);
            if (slow != null) {
                slow.deviceIds = deviceIds;
                running = slow.answer;
                slow.answer = null;
                if (running == null) {
                    if (!slow.running)
                        confirmOnItsOwn(nodeIndex, slow);
                    else
                        probeOnItsOwn(nodeIndex, slow);
                    log.debug("devices duplicated with node-index: {}, but that node is slow to confirm them and asked on its own, keep devices for now: {}", nodeIndex, String.join(", ", deviceIds));
                    return;
                }
                // it answered within the probe timeout again: asked by the check itself from now on
                if (slow.budget == null)
                    slowConfirmations.remove(nodeIndex);
            }
        }
        if (running == null) {
            try {
                running = confirmDeviceStatus(nodeIndex, probeTimeout);
            } catch (Throwable e) {
                if (isTimeout(e)) {
                    synchronized (slowConfirmations) {
                        var slow = new SlowConfirmation();
                        slow.deviceIds = deviceIds;
                        slowConfirmations.put(nodeIndex, slow);
                        timedOut(nodeIndex, slow, probeTimeout, e);
                        confirmOnItsOwn(nodeIndex, slow);
                    }
                } else {
                    // checked again on the next run
                    log.warn("devices duplicated with node-index: {}, but that node could not confirm them, keep devices for now: {}::{}", nodeIndex, String.join(", ", deviceIds), errorParser(e));
                }
                return;
            }
        }
        var yielded = new TreeSet<String>();
        var kept = new TreeSet<String>();
        for (var deviceId : deviceIds) {
            if (running.containsKey(deviceId))
                yielded.add(deviceId);
            else
                kept.add(deviceId);
        }
        if (!kept.isEmpty())
            log.info("devices registered on node-index: {}, but not running there, keep devices: {}", nodeIndex, String.join(", ", kept));
        if (yielded.isEmpty())
            return;
        synchronized (driverMutex) {
            // a device duplicated with another lower node as well may have been yielded to that one meanwhile
            yielded.retainAll(driverProtocols.keySet());
            if (yielded.isEmpty())
                return;
            log.info("devices duplicated with node-index: {}, yield (disconnect) devices: {}", nodeIndex, String.join(", ", yielded));
            disconnectList(yielded, true);
        }
    }

    /**
     * Leaves out of the failover of the lost nodeIndex the devices it runs, when it is a member again: e.g. this node
     * was the one partitioned away, and the partition healed while the failover waited. Only the devices it confirms,
     * so a restarted node that does not run them still gets them failed over; without an answer they are failed over,
     * as a device then found on both nodes is resolved by the duplicate check
     */
    private void skipDevicesRunningOnMember(int nodeIndex, Set<Device> devices) {
        if (devices.isEmpty() || !clusterStarter.getCluster().contains(nodeIndex))
            return;
        Map<String, StatusCode> running;
        try {
            running = confirmDeviceStatus(nodeIndex, probeTimeout);
        } catch (Throwable e) {
            log.warn("node(node-index={}) deleted and a member again, but it could not confirm its devices, connect them::{}", nodeIndex, errorParser(e));
            return;
        }
        var skipped = devices.stream()
                .map(Device::getId)
                .filter(deviceId -> deviceId != null && running.containsKey(deviceId))
                .collect(Collectors.toCollection(TreeSet::new));
        if (skipped.isEmpty()) {
            log.info("node(node-index={}) deleted and a member again, but it does not run its devices, connect them", nodeIndex);
            return;
        }
        log.info("node(node-index={}) deleted, but it is a member again and runs them, skip devices: {}", nodeIndex, String.join(", ", skipped));
        devices.removeIf(device -> device.getId() != null && skipped.contains(device.getId()));
    }

    /**
     * the devices nodeIndex runs, asked within timeout instead of the read timeout: a slow peer counts as not confirming.
     * The node is looked up first and called after, so the caller logs a call that fails (a slow peer is asked again and
     * again, see {@link #timedOut})
     */
    private Map<String, StatusCode> confirmDeviceStatus(int nodeIndex, Duration timeout) throws Throwable {
        AtomicReference<String> url = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, url::set, "confirm device status for node-index: " + nodeIndex);
        if (result != null) throw result;
        Map<String, StatusCode> ret = callNode(url.get(), "GET", "/device-status", null, STATUS_MAP, Map.of(), timeout);
        return Objects.requireNonNullElse(ret, Map.of());
    }

    /** a lower node that did not confirm its duplicates within the probe timeout (guarded by slowConfirmations) */
    private static final class SlowConfirmation {
        /** the timeout of its next confirmation, on a task of its own; null once it answered within the probe timeout again */
        Duration budget;
        /** timeouts in a row: only the first is logged as a warning */
        int timeouts;
        /** a confirmation is running on a task of its own */
        boolean running;
        /** that confirmation, cancelled once a probe is answered, and when it started (System.nanoTime) */
        Disposable request = Disposable.disposed();
        long requestStarted;
        /** a probe is running: the node asked within the probe timeout on a task of its own, while that confirmation runs */
        boolean probing;
        /** what it answered on a task of its own, for the next check run to use */
        Map<String, StatusCode> answer;
        /** the devices duplicated with it, as the last check run found them */
        Set<String> deviceIds = Set.of();
    }

    /**
     * Asks nodeIndex within its budget on a task of its own, so the single-flight check is not held meanwhile. Once it
     * answers or times out, the check runs again: it uses the answer, or asks again with twice the budget. Holding
     * slowConfirmations
     */
    private void confirmOnItsOwn(int nodeIndex, SlowConfirmation slow) {
        slow.running = true;
        slow.requestStarted = System.nanoTime();
        var budget = Objects.requireNonNullElse(slow.budget, probeTimeout);
        slow.request = Completable.fromAction(() -> {
                    Map<String, StatusCode> answer = null;
                    Throwable failure = null;
                    var started = System.nanoTime();
                    try {
                        answer = confirmDeviceStatus(nodeIndex, budget);
                    } catch (Throwable e) {
                        failure = e;
                    }
                    var elapsed = Duration.ofNanos(System.nanoTime() - started);
                    var checkAgain = false;
                    synchronized (slowConfirmations) {
                        // cancelled once a probe was answered, or forgotten on dispose
                        if (Thread.currentThread().isInterrupted() || slowConfirmations.get(nodeIndex) != slow)
                            return;
                        slow.running = false;
                        if (answer != null) {
                            log.debug("node-index: {} confirmed its devices after {} ms", nodeIndex, elapsed.toMillis());
                            slow.answer = answer;
                            slow.timeouts = 0;
                            // a node that is still slow keeps its budget
                            if (elapsed.compareTo(probeTimeout) <= 0)
                                slow.budget = null;
                            checkAgain = true;
                        } else if (isTimeout(failure)) {
                            timedOut(nodeIndex, slow, budget, failure);
                            checkAgain = true;
                        } else {
                            // asked again by the next run
                            log.warn("devices duplicated with node-index: {}, but that node could not confirm them, keep devices for now: {}::{}", nodeIndex, String.join(", ", slow.deviceIds), errorParser(failure));
                        }
                    }
                    if (checkAgain && !Thread.currentThread().isInterrupted())
                        checkDuplicatedDevices();
                })
                .subscribeOn(Schedulers.io())
                .subscribe(() -> {}, e -> log.error("confirm device status for node-index: {} failed", nodeIndex, e), slowConfirmationTasks);
    }

    /**
     * Asks nodeIndex within the probe timeout on a task of its own, while its confirmation within a longer budget runs:
     * a node that answers that one late (e.g. a request stuck in it that it no longer serves) is then confirmed within
     * about one check interval of recovering, not once that one answers or times out. Whichever answers first is used:
     * an answer here resets the budget, and the confirmation still running is cancelled, so its late answer is never
     * used. A probe that fails changes nothing and is logged at debug level only; that confirmation still runs, so a node
     * that stays slow still confirms. One probe runs at a time, and none before that confirmation has run the probe
     * timeout (it would answer as soon). Holding slowConfirmations
     */
    private void probeOnItsOwn(int nodeIndex, SlowConfirmation slow) {
        if (slow.probing || System.nanoTime() - slow.requestStarted < probeTimeout.toNanos())
            return;
        slow.probing = true;
        var timeout = probeTimeout;
        Completable.fromAction(() -> {
                    Map<String, StatusCode> answer = null;
                    Throwable failure = null;
                    try {
                        answer = confirmDeviceStatus(nodeIndex, timeout);
                    } catch (Throwable e) {
                        failure = e;
                    }
                    synchronized (slowConfirmations) {
                        // forgotten, or cancelled on dispose
                        if (Thread.currentThread().isInterrupted() || slowConfirmations.get(nodeIndex) != slow)
                            return;
                        slow.probing = false;
                        if (answer == null) {
                            log.debug("devices duplicated with node-index: {}, but that node did not answer a probe within {} ms either, keep devices for now: {}::{}",
                                    nodeIndex, timeout.toMillis(), String.join(", ", slow.deviceIds), errorParser(failure));
                            return;
                        }
                        log.debug("node-index: {} confirmed its devices to a probe within {} ms{}", nodeIndex, timeout.toMillis(),
                                slow.running ? ", cancel its confirmation still running on its own" : "");
                        slow.answer = answer;
                        slow.timeouts = 0;
                        slow.budget = null;
                        if (slow.running) {
                            slow.running = false;
                            slow.request.dispose();
                        }
                    }
                    if (!Thread.currentThread().isInterrupted())
                        checkDuplicatedDevices();
                })
                .subscribeOn(Schedulers.io())
                .subscribe(() -> {}, e -> log.error("probe device status of node-index: {} failed", nodeIndex, e), slowConfirmationTasks);
    }

    /**
     * A confirmation of nodeIndex that did not answer within timeout: the next one gets twice as long, up to the read
     * timeout. Holding slowConfirmations
     */
    private void timedOut(int nodeIndex, SlowConfirmation slow, Duration timeout, Throwable e) {
        var doubled = timeout.multipliedBy(2);
        slow.budget = doubled.compareTo(readTimeout) < 0 ? doubled : readTimeout;
        // asked again and again while it stays slow: only the first timeout in a row above debug
        if (++slow.timeouts == 1)
            log.warn("devices duplicated with node-index: {}, but that node did not confirm them within {} ms, keep devices for now and ask it on its own within {} ms: {}::{}",
                    nodeIndex, timeout.toMillis(), slow.budget.toMillis(), String.join(", ", slow.deviceIds), errorParser(e));
        else
            log.debug("devices duplicated with node-index: {}, but that node did not confirm them within {} ms ({} in a row), keep devices for now and ask it on its own within {} ms: {}::{}",
                    nodeIndex, timeout.toMillis(), slow.timeouts, slow.budget.toMillis(), String.join(", ", slow.deviceIds), errorParser(e));
    }

    /** the peer was reached but did not answer in time; a connect timeout is an unreachable peer, not a slow one */
    private static boolean isTimeout(Throwable e) {
        for (int depth = 0; e != null && depth < 16; e = e.getCause(), depth++) {
            if (e instanceof HttpConnectTimeoutException)
                return false;
            if (e instanceof HttpTimeoutException)
                return true;
        }
        return false;
    }

    /**
     * Forgets the slow lower nodes that no longer hold a duplicate here, with their answers: an answer is used only by a
     * check run that still finds the duplicate. One still being asked is kept until it has answered
     */
    private void forgetSlowConfirmations(Set<Integer> duplicatedWith) {
        synchronized (slowConfirmations) {
            slowConfirmations.entrySet().removeIf(entry -> !entry.getValue().running && !duplicatedWith.contains(entry.getKey()));
        }
    }

    /** the timeout of the next confirmation of nodeIndex on a task of its own; null when the check asks it within the probe timeout */
    Duration slowConfirmationBudget(int nodeIndex) {
        synchronized (slowConfirmations) {
            var slow = slowConfirmations.get(nodeIndex);
            return slow == null ? null : slow.budget;
        }
    }

    /**
     * deviceId -> current setting of the devices: the entry in this node's own shared object (a copy), which also holds
     * the data their scripts wrote since the connect; the setting they were connected with when there is no valid entry
     */
    private Map<String, Device> currentSettings(Collection<DriverProtocol> protocols) {
        var own = clusterStarter.getSharedObject();
        var ret = new HashMap<String, Device>();
        for (var protocol : protocols) {
            Device current = null;
            var value = own == null ? null : own.get(protocol.deviceId);
            if (DriverStarter.isDeviceEntry(value)) {
                try {
                    current = driverStarter.objectMapper.convertValue(value, Device.class);
                } catch (IllegalArgumentException e) {
                    log.warn("[{}] invalid device setting in the shared object, remember the connected one", protocol.deviceId, e);
                }
            }
            ret.put(protocol.deviceId, current != null && protocol.deviceId.equals(current.getId()) ? current : protocol.device);
        }
        return ret;
    }

    /** one entry of a removed node's shared object as the Device to fail over, null (logged) when it cannot be */
    @SuppressWarnings("unchecked")
    private Device failoverDevice(int nodeIndex, String key, Object value) {
        if (!DriverStarter.isDeviceEntry(value)) {
            log.warn("[{}] node(node-index={}) deleted, entry is not a device setting, skip it", key, nodeIndex);
            return null;
        }
        var setting = new HashMap<>((Map<String, Object>) value);
        if (setting.containsKey("data") && !(setting.get("data") instanceof Map)) {
            log.warn("[{}] node(node-index={}) deleted, device data is not an object, drop it: {}", key, nodeIndex, setting.get("data"));
            setting.remove("data");
        }
        try {
            return driverStarter.objectMapper.convertValue(setting, Device.class);
        } catch (IllegalArgumentException e) {
            log.error("[{}] node(node-index={}) deleted, invalid device setting, skip it", key, nodeIndex, e);
            return null;
        }
    }

    Map<String, Map<String, Response>> getResponse(int nodeIndex) throws Throwable {
        AtomicReference<Map<String, Map<String, Response>>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "GET", "/response", null, RESPONSE_MAPS)),
                "get response map for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    Map<String, Response> getResponse(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<Map<String, Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "GET", "/response/" + deviceId, null, RESPONSE_MAP)),
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
                        ret.set(callNode(targetUrl, "GET", "/device-status", null, STATUS_MAP)),
                "get device status map for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    StatusCode getDeviceStatus(int nodeIndex, String deviceId) throws Throwable {
        AtomicReference<StatusCode> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                ret.set(callNode(targetUrl, "GET", "/device-status/" + deviceId, null, STATUS_CODE)),
                "get device status for node-index: " + nodeIndex + ", device-id: " + deviceId);
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
                        ret.set(callNode(targetUrl, "POST", "/execute-commands/" + deviceId, commands,
                                LIST_OF_RESPONSE, initialValueHeader(initialValue))),
                "execute commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> requestCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "POST", "/request-commands/" + deviceId, commands,
                                LIST_OF_RESPONSE, initialValueHeader(initialValue))),
                "request commands for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + UtilFunc.joinCommandId(commands));
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> executeCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "POST", "/execute-command-ids/" + deviceId, commandIdList,
                                LIST_OF_RESPONSE, initialValueHeader(initialValue))),
                "execute command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (result != null) throw result;
        return ret.get();
    }

    List<Response> requestCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        AtomicReference<List<Response>> ret = new AtomicReference<>();
        var result = clusterStarter.toIndexFunc(nodeIndex, targetUrl ->
                        ret.set(callNode(targetUrl, "POST", "/request-command-ids/" + deviceId, commandIdList,
                                LIST_OF_RESPONSE, initialValueHeader(initialValue))),
                "request command ids for node-index: " + nodeIndex + ", device-id: " + deviceId + ", commands: " + commandIdList);
        if (result != null) throw result;
        return ret.get();
    }

    /**
     * node-to-node call over the cluster's shared HTTP client, so driver traffic reuses
     * the same connection pool per peer as the cluster's own internal calls.
     */
    private <T> T callNode(String targetUrl, String method, String path, Object body, JavaType responseType) {
        return callNode(targetUrl, method, path, body, responseType, Map.of());
    }

    private <T> T callNode(String targetUrl, String method, String path, Object body,
                           JavaType responseType, Map<String, String> headers) {
        return clusterStarter.getNodeHttpClient().call(targetUrl + driverBasePath + path, method, body, responseType, headers);
    }

    private <T> T callNode(String targetUrl, String method, String path, Object body,
                           JavaType responseType, Map<String, String> headers, Duration timeout) {
        return clusterStarter.getNodeHttpClient().call(targetUrl + driverBasePath + path, method, body, responseType, headers, timeout);
    }

    /**
     * The routes read {@code initial-value} from a request header; header values only carry
     * Latin-1, so the value is sent URL-encoded (UTF-8) and the routes decode it.
     * A null value is simply omitted, which the route reads back as null.
     */
    private Map<String, String> initialValueHeader(String initialValue) {
        return initialValue == null ? Map.of()
                : Map.of("initial-value", URLEncoder.encode(initialValue, StandardCharsets.UTF_8));
    }
}
