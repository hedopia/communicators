package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.cluster.ClusterEvents;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import com.sds.communicators.common.type.Position;
import com.sds.communicators.common.type.StatusCode;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.functions.Consumer;
import org.graalvm.polyglot.PolyglotException;
import org.javatuples.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cluster-event handling of {@link DriverService} on one real but never started node (index {@value SELF}).
 * The only peer is a fake HTTP node (index 0) that always reports itself as the leader, so a request forwarded
 * to the leader is observable, and that reports to run the devices a test sets (device-status);
 * device registrations are recorded instead of being written to the shared object,
 * whose replicas the tests set directly.
 */
class DriverServiceClusterTest {
    private static final int HEARTBEAT_MILLIS = 200;
    private static final int SELF = 1;
    private static final String NOT_ACTIVATED = "connect failed, cluster is not activated (quorum not reached)";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> forwarded = new CopyOnWriteArrayList<>();
    private final List<String> forwardedBodies = new CopyOnWriteArrayList<>();
    /** the HTTP status the fake leader answers forwarded requests with */
    private volatile int forwardedStatus = 200;
    /** the devices the fake node (index 0) reports to run */
    private final Map<String, StatusCode> peerDeviceStatus = new ConcurrentHashMap<>();
    /** device-status requests the fake node received, i.e. duplicate checks that asked it to confirm */
    private final AtomicInteger peerDeviceStatusRequests = new AtomicInteger();
    /** when set, the fake node answers device-status only once it is opened (at most 10 s) */
    private volatile CountDownLatch peerDeviceStatusGate = null;
    private ExecutorService fakeLeaderExecutor;
    private HttpServer fakeLeader;
    private TestDriverStarter driverStarter;
    private ClusterStarter clusterStarter;
    private DriverService driverService;

    static class TestDriverStarter extends DriverStarter {
        final List<Map<String, Device>> addedDevices = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> deletedDevices = new CopyOnWriteArrayList<>();
        /** also write the registrations to this node's own object */
        boolean writeSharedObject = false;
        /** called with every status a device sends, on the thread that changed it */
        volatile java.util.function.Consumer<Status> statusListener = status -> {};
        /** the number of next device-id-map reads that throw */
        final AtomicInteger deviceIdMapFailures = new AtomicInteger();

        TestDriverStarter(ClusterStarter.Builder clusterStarterBuilder) throws Exception {
            super("test-driver", false, "", null, "/driver", null, null, clusterStarterBuilder);
        }

        @Override
        public Map<Integer, Set<String>> getDeviceIdMap() {
            if (deviceIdMapFailures.getAndUpdate(failures -> Math.max(failures - 1, 0)) > 0)
                throw new IllegalStateException("device-id-map read failed (test)");
            return super.getDeviceIdMap();
        }

        @Override
        protected void sendResponse(List<Response> responses, String driverId, int nodeIndex) {}

        @Override
        protected void sendStatus(Status deviceStatus, String driverId, int nodeIndex) {
            statusListener.accept(deviceStatus);
        }

        @Override
        void addDevices(Map<String, Device> deviceMap) throws JsonProcessingException {
            addedDevices.add(deviceMap);
            if (writeSharedObject)
                super.addDevices(deviceMap);
        }

        @Override
        void deleteDevices(Map<String, String> deleteResults) {
            deletedDevices.add(deleteResults);
            if (writeSharedObject)
                super.deleteDevices(deleteResults);
        }
    }

    /** a registered device that records status changes instead of driving a transport or python */
    static class StubProtocol extends DriverProtocolDummy {
        final List<StatusCode> requested = new CopyOnWriteArrayList<>();
        /** the answer to a status change, null for success */
        volatile String changeStatusResult = null;

        StubProtocol(Device device) {
            this.device = device;
            this.deviceId = device.getId();
        }

        @Override
        String changeStatus(StatusCode desiredStatus) {
            requested.add(desiredStatus);
            return changeStatusResult;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        fakeLeader = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeLeader.createContext("/", exchange -> {
            var requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.equals("/index")) {
                body = "0";
            } else if (path.equals("/cluster/internal/node-status")) {
                body = "{\"nodeIndex\":0,\"position\":\"LEADER\"}";
            } else if (path.equals("/driver/device-status")) {
                peerDeviceStatusRequests.incrementAndGet();
                var gate = peerDeviceStatusGate;
                if (gate != null) {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                body = json.writeValueAsString(peerDeviceStatus);
            } else {
                forwarded.add(exchange.getRequestMethod() + " " + path);
                forwardedBodies.add(requestBody);
                body = "{\"dev_a\":\"forwarded\"}";
                status = forwardedStatus;
            }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        // concurrent requests are served concurrently, so one held at the gate does not delay the others
        fakeLeaderExecutor = Executors.newCachedThreadPool();
        fakeLeader.setExecutor(fakeLeaderExecutor);
        fakeLeader.start();

        int selfPort;
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            selfPort = socket.getLocalPort();
        }
        var targetUrls = Set.of("http://127.0.0.1:" + selfPort, "http://127.0.0.1:" + fakeLeader.getAddress().getPort());
        driverStarter = new TestDriverStarter(ClusterStarter.builder(targetUrls, selfPort, SELF)
                .setHeartbeatSendingIntervalMillis(HEARTBEAT_MILLIS));
        clusterStarter = driverStarter.getClusterStarter();
        driverService = (DriverService) field(DriverStarter.class, "driverService").get(driverStarter);
    }

    @AfterEach
    void tearDown() {
        var gate = peerDeviceStatusGate;
        if (gate != null)
            gate.countDown();
        for (var protocol : driverService.driverProtocols.values()) {
            if (!(protocol instanceof StubProtocol)) {
                protocol.changeStatus(StatusCode.DISCONNECTED);
                protocol.driverCommand.pythonEngine.close();
            }
        }
        driverService.driverProtocols.clear();
        // stops a periodic duplicate check a test started
        driverService.dispose();
        clusterStarter.dispose();
        fakeLeader.stop(0);
        fakeLeaderExecutor.shutdownNow();
    }

    @Test
    void connectAllToLeaderIsRefusedBelowQuorum() throws Exception {
        var devices = Set.of(device("dev_a"), device(null));

        setCluster(Position.LEADER, false);
        assertEquals(Map.of("dev_a", NOT_ACTIVATED, "null", NOT_ACTIVATED), driverService.connectAllToLeader(SELF, devices));

        setCluster(Position.FOLLOWER, false);
        assertEquals(Map.of("dev_a", NOT_ACTIVATED, "null", NOT_ACTIVATED), driverService.connectAllToLeader(SELF, devices));

        assertTrue(driverStarter.addedDevices.isEmpty());
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void deletedNodeDevicesAreTakenOverOnlyWithQuorum() throws Throwable {
        setCluster(Position.LEADER, false);
        clusterDeletedHandler().accept(2, sharedObjectOf(device("dev_a")));
        assertTrue(driverStarter.addedDevices.isEmpty());
        assertTrue(forwarded.isEmpty());

        // with quorum the same event reaches connectAll (the unknown protocol keeps anything from starting)
        setCluster(Position.LEADER, true);
        clusterDeletedHandler().accept(2, sharedObjectOf(device("dev_a")));
        assertEquals(1, driverStarter.addedDevices.size());
    }

    @Test
    void failoverWaitsUntilAPartitionedNodeHasStartedToDropItsDevices() throws Throwable {
        setCluster(Position.LEADER, true);
        var start = System.nanoTime();
        clusterDeletedHandler().accept(2, sharedObjectOf(device("dev_a")));

        // that node's timers fire up to an interval later than this node's, and it then waits two intervals
        assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(3L * HEARTBEAT_MILLIS));
        assertEquals(1, driverStarter.addedDevices.size());
    }

    @Test
    void failoverSkipsOnlyTheEntriesThatAreNoDeviceSettings() throws Throwable {
        setCluster(Position.LEADER, true);
        var object = new HashMap<String, Object>(sharedObjectOf(dummyDevice("dev_a")));
        var scalarData = setting(dummyDevice("dev_b"));
        scalarData.put("data", 5);
        object.put("dev_b", scalarData);
        var invalid = setting(dummyDevice("dev_c"));
        invalid.put("socketTimeout", "not a number");
        object.put("dev_c", invalid);
        object.put("no_id", setting(dummyDevice(null)));
        // script data left behind without a registration
        object.put("ghost", new HashMap<>(Map.of("data", Map.of("x", 1))));

        clusterDeletedHandler().accept(2, object);

        assertEquals(Set.of("dev_a", "dev_b"), driverService.driverProtocols.keySet());
        assertEquals(Map.of(), driverService.driverProtocols.get("dev_b").device.getData());
        assertEquals(1, driverStarter.addedDevices.size());
        assertEquals(Set.of("dev_a", "dev_b"), driverStarter.addedDevices.getFirst().keySet());
    }

    @Test
    void failoverLeavesOutTheDevicesThatTheLostNodeRunsOnceItIsAMemberAgain() throws Throwable {
        setCluster(Position.LEADER, true);
        var object = new HashMap<String, Object>(sharedObjectOf(dummyDevice("dev_a")));
        object.putAll(sharedObjectOf(dummyDevice("dev_b")));
        object.put("no_id", setting(dummyDevice(null)));
        // the partition healed while the failover waited: node 0 is a member again and still runs dev_a, which its
        // replica here does not show yet
        addMember(0);
        peerRuns("dev_a");

        clusterDeletedHandler().accept(0, object);

        assertEquals(1, peerDeviceStatusRequests.get());
        assertEquals(Set.of("dev_b"), driverService.driverProtocols.keySet());
        assertEquals(List.of(Set.of("dev_b")), driverStarter.addedDevices.stream().map(Map::keySet).toList());
    }

    @Test
    void failoverOfALostNodeThatIsAMemberAgainConnectsWhatItDoesNotConfirm() throws Throwable {
        setCluster(Position.LEADER, true);
        // not a member: failed over without asking it
        clusterDeletedHandler().accept(0, sharedObjectOf(dummyDevice("dev_a")));
        assertEquals(0, peerDeviceStatusRequests.get());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());

        // restarted and a member again, but it does not run dev_b: membership alone does not keep it from the failover
        addMember(0);
        clusterDeletedHandler().accept(0, sharedObjectOf(dummyDevice("dev_b")));
        assertEquals(1, peerDeviceStatusRequests.get());
        assertEquals(Set.of("dev_a", "dev_b"), driverService.driverProtocols.keySet());

        // it runs dev_c, but answers only after the probe timeout: dev_c is failed over, and the duplicate check resolves it
        peerRuns("dev_c");
        peerDeviceStatusGate = new CountDownLatch(1);
        clusterDeletedHandler().accept(0, sharedObjectOf(dummyDevice("dev_c")));
        assertEquals(2, peerDeviceStatusRequests.get());
        assertEquals(Set.of("dev_a", "dev_b", "dev_c"), driverService.driverProtocols.keySet());
    }

    @Test
    void failoverWaitsForTheMembershipToSettleBeforeCheckingQuorum() throws Throwable {
        // a partitioned leader still counts the other lost peer when the first peer's timer fires
        setCluster(Position.LEADER, true);
        var handler = new Thread(() -> {
            try {
                clusterDeletedHandler().accept(2, sharedObjectOf(device("dev_a")));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
        handler.setDaemon(true);
        handler.start();
        Thread.sleep(HEARTBEAT_MILLIS / 2);
        // ...and the second peer's timer fires within the settle period, dropping this side below quorum
        setCluster(Position.LEADER, false);
        handler.join(10_000);

        assertFalse(handler.isAlive());
        assertTrue(driverStarter.addedDevices.isEmpty());
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void connectAllForwardsToLeaderWhenLeadershipIsLostWhileWaitingForTheMutex() throws Exception {
        setCluster(Position.LEADER, true);
        var mutex = field(DriverService.class, "connectAllMutex").get(driverService);
        var result = new CompletableFuture<Map<String, String>>();
        var caller = new Thread(() -> {
            try {
                result.complete(driverService.connectAllToLeader(SELF, Set.of(device("dev_a"))));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        synchronized (mutex) {
            caller.start();
            awaitBlockedOn(caller, mutex);
            // e.g. the other former leader holds the mutex and this node steps down after the split brain heals
            setCluster(Position.FOLLOWER, true);
        }

        assertEquals(Map.of("dev_a", "forwarded"), result.get(30, TimeUnit.SECONDS));
        assertEquals(List.of("POST /driver/internal/connect-all-to-leader/" + SELF), forwarded);
        assertTrue(driverStarter.addedDevices.isEmpty());
    }

    @Test
    void connectAllToLeaderRejectsAMissingOrInvalidDeviceId() throws Exception {
        setCluster(Position.LEADER, true);
        var result = driverService.connectAllToLeader(SELF, Set.of(device(null), device("bad-id!")));
        assertEquals(Map.of("null", "connect failed, invalid device-id", "bad-id!", "connect failed, invalid device-id"), result);
        assertTrue(driverStarter.addedDevices.isEmpty());
    }

    @Test
    void connectAllKeepsADeviceAlreadyRunningOnThisNode() {
        var running = register("dev_a");

        var result = driverService.connectAll(Set.of(device("dev_a"), device("dev_b")));

        assertEquals("connect failed, device is already connected on this node", result.get("dev_a"));
        assertTrue(result.get("dev_b").startsWith("connect failed::"), result.get("dev_b"));
        assertSame(running, driverService.driverProtocols.get("dev_a"));
        assertTrue(running.requested.isEmpty());
        assertTrue(driverStarter.addedDevices.stream().noneMatch(devices -> devices.containsKey("dev_a")));
    }

    @Test
    void lateScriptDataOfADeletedDeviceIsRejectedWithoutLeavingAGhost() throws Exception {
        setCluster(Position.LEADER, true);
        setDeviceIds(SELF, "dev_a");
        var protocol = new DriverProtocolDummy().create(driverService, "", dummyDevice("dev_a"));
        try {
            protocol.setData(Map.of("t", 1));
            protocol.setData(2, List.of("n", "m"));
            protocol.deleteData(List.of("t"));
            assertEquals(Map.of("n", Map.of("m", 2)), protocol.getData(List.of()));

            // the device is disconnected and its registration deleted while one of its scripts still runs
            clusterStarter.deleteSharedObject(List.of(List.of("dev_a")));
            var expected = "device dev_a is not registered on this node";
            assertEquals(expected, assertThrows(IllegalStateException.class, () -> protocol.setData(Map.of("t", 2))).getMessage());
            assertEquals(expected, assertThrows(IllegalStateException.class, () -> protocol.setData(3, List.of("x"))).getMessage());
            assertEquals(expected, assertThrows(IllegalStateException.class, () -> protocol.deleteData(List.of("n"))).getMessage());
            var scriptFailure = assertThrows(PolyglotException.class,
                    () -> protocol.driverCommand.pythonEngine.exec("protocol.setData({'t': 2})"));
            assertInstanceOf(IllegalStateException.class, scriptFailure.asHostException());

            assertEquals(Map.of(), clusterStarter.getSharedObject());
            assertEquals(Set.of(), driverStarter.getDeviceIdMap().get(SELF));
        } finally {
            protocol.driverCommand.pythonEngine.close();
        }
    }

    @Test
    void protocolScriptDataWrittenWhileConnectingIsKeptUnderTheConfiguredData() throws Exception {
        setCluster(Position.LEADER, true);
        setDeviceIds(SELF);
        driverStarter.writeSharedObject = true;
        // http-client runs its protocolScript when built and connects without a transport
        var initialized = device("dev_a");
        initialized.setConnectionUrl("http-client://127.0.0.1:1");
        initialized.setData(new HashMap<>(Map.of("configured", 1, "both", "configured")));
        initialized.setProtocolScript("protocol.setData({'initialized': 2, 'both': 'script'})\n");
        var failing = device("dev_b");
        failing.setConnectionUrl("http-client://127.0.0.1:1");
        failing.setProtocolScript("protocol.setData({'initialized': 3})\nraise Exception('init failed')\n");

        var result = driverService.connectAll(Set.of(initialized, failing));

        assertEquals("connected", result.get("dev_a"));
        assertTrue(result.get("dev_b").startsWith("connect failed::"), result.get("dev_b"));
        assertEquals(Map.of("configured", 1, "initialized", 2, "both", "configured"),
                driverService.driverProtocols.get("dev_a").getData(List.of()));
        // the device that was not registered leaves nothing behind
        assertEquals(Set.of("dev_a"), clusterStarter.getSharedObject().keySet());
        assertEquals(Map.of(SELF, Set.of("dev_a")), driverStarter.getDeviceIdMap());
        assertTrue(driverService.building.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scriptDataOfADeviceStaysAnObject() throws Exception {
        setCluster(Position.LEADER, true);
        setDeviceIds(SELF, "dev_a");
        var protocol = new DriverProtocolDummy().create(driverService, "", dummyDevice("dev_a"));
        try {
            protocol.setData(Map.of("t", 1), List.of());
            assertEquals("setData value must be a dict when the path is empty",
                    assertThrows(IllegalArgumentException.class, () -> protocol.setData(5, List.of())).getMessage());
            assertThrows(IllegalArgumentException.class, () -> protocol.setData(List.of(1), List.of()));
            assertThrows(IllegalArgumentException.class, () -> protocol.setData(null));
            assertEquals(Map.of("t", 1), protocol.getData(List.of()));

            // getData returns a copy
            ((Map<String, Object>) protocol.getData(List.of())).put("t", 9);
            assertEquals(1, protocol.getData(List.of("t")));
        } finally {
            protocol.driverCommand.pythonEngine.close();
        }
    }

    @Test
    void deviceIdMapCountsOnlyDeviceSettings() throws Exception {
        setCluster(Position.LEADER, true);
        setDeviceIds(SELF, "dev_a");
        replicas().get(SELF).put("ghost", new HashMap<>(Map.of("data", new HashMap<>(Map.of("x", 1)))));
        setDeviceIds(2, "dev_b");

        var deviceIdMap = driverStarter.getDeviceIdMap();
        assertEquals(Map.of(SELF, Set.of("dev_a"), 2, Set.of("dev_b")), deviceIdMap);
        // detached from the shared object
        deviceIdMap.get(SELF).add("dev_x");
        assertEquals(Set.of("dev_a"), driverStarter.getDeviceIdMap().get(SELF));

        // a ghost entry does not keep its device from being connected again
        var result = driverService.connectAllToLeader(SELF, Set.of(device("ghost")));
        assertTrue(result.get("ghost").startsWith("connect failed::"), result.get("ghost"));
    }

    @Test
    void inactivationThatRevertsWithinTheSettlePeriodKeepsTheDevices() throws Throwable {
        setCluster(Position.LEADER, false);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        var handler = new Thread(() -> {
            try {
                inactivatedHandler().run();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
        handler.setDaemon(true);
        handler.start();
        Thread.sleep(HEARTBEAT_MILLIS / 2);
        // e.g. queued heartbeats arrive right after a GC pause
        setCluster(Position.LEADER, true);
        handler.join(10_000);

        assertFalse(handler.isAlive());
        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
        assertTrue(driverStarter.deletedDevices.isEmpty());
    }

    @Test
    void devicesDroppedOnInactivationAreReconnectedOnActivationUnlessRegisteredElsewhere() throws Throwable {
        setCluster(Position.LEADER, false);
        var kept = register(dummyDevice("dev_a"));
        var failedOver = register(dummyDevice("dev_b"));
        setDevices(SELF, kept.device, failedOver.device);

        inactivatedHandler().run();

        assertEquals(List.of(StatusCode.DISCONNECTED), kept.requested);
        assertEquals(List.of(StatusCode.DISCONNECTED), failedOver.requested);
        assertTrue(driverService.driverProtocols.isEmpty());
        assertEquals(1, driverStarter.deletedDevices.size());

        // meanwhile the registrations are gone and the majority failed dev_b over to node 2
        setDeviceIds(SELF);
        setDeviceIds(2, "dev_b");
        setCluster(Position.LEADER, true);
        activatedHandler().run();

        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
        assertFalse(driverService.driverProtocols.get("dev_a") instanceof StubProtocol);
        assertEquals(List.of(Set.of("dev_a")), driverStarter.addedDevices.stream().map(Map::keySet).toList());

        // reconnected once only
        activatedHandler().run();
        assertEquals(1, driverStarter.addedDevices.size());
    }

    @Test
    void overwriteOfOwnOrUnknownObjectKeepsLocalDevices() throws Throwable {
        setCluster(Position.LEADER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");

        overwrittenHandler().accept(SELF);
        overwrittenHandler().accept(3);

        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
        assertTrue(driverStarter.deletedDevices.isEmpty());
        assertTrue(driverStarter.addedDevices.isEmpty());
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void duplicateWithHigherNodeIndexIsKeptHere() throws Throwable {
        setCluster(Position.LEADER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(2, "dev_a");
        addMember(2);

        overwrittenHandler().accept(2);

        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
        assertTrue(driverStarter.deletedDevices.isEmpty());
    }

    @Test
    void duplicateWithLowerNodeIndexIsYieldedWithoutReconnect() throws Throwable {
        setCluster(Position.LEADER, true);
        var duplicated = register("dev_a");
        var unique = register("dev_b");
        setDeviceIds(SELF, "dev_a", "dev_b");
        setDeviceIds(0, "dev_a", "dev_c");
        addMember(0);
        peerRuns("dev_a", "dev_c");

        overwrittenHandler().accept(0);

        assertEquals(List.of(StatusCode.DISCONNECTED), duplicated.requested);
        assertTrue(unique.requested.isEmpty());
        assertEquals(Set.of("dev_b"), driverService.driverProtocols.keySet());
        assertEquals(List.of(Map.of("dev_a", "disconnected")), driverStarter.deletedDevices);
        assertTrue(driverStarter.addedDevices.isEmpty());
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void duplicateWithARemovedNodeIsCheckedOnlyOnceItJoinsAgain() throws Throwable {
        setCluster(Position.LEADER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        // node 0 is no longer a member: its replica only waits for the leader's failover, which moved dev_a here
        setDeviceIds(0, "dev_a");

        overwrittenHandler().accept(0);
        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());

        // it was only partitioned away and still runs dev_a
        addMember(0);
        peerRuns("dev_a");
        clusterAddedHandler().accept(0);
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
    }

    @Test
    void periodicCheckYieldsADuplicateThatNoEventReported() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        addMember(0);
        peerRuns("dev_a");
        driverService.start();

        // the lower node 0 registers dev_a as well, e.g. the leader's failover connected it there while this node
        // stalled; its replica here gains dev_a through a delta, which fires neither overwritten nor cluster-added
        setDeviceIds(0, "dev_a");

        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
        assertEquals(List.of(Map.of("dev_a", "disconnected")), driverStarter.deletedDevices);
        assertTrue(driverStarter.addedDevices.isEmpty());
        assertTrue(forwarded.isEmpty());
    }

    @Test
    void periodicCheckStopsOnDispose() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        driverService.start();
        driverService.dispose();

        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        peerRuns("dev_a");
        // two and a half check periods
        Thread.sleep(25L * HEARTBEAT_MILLIS / 2);

        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
    }

    @Test
    void duplicateIsYieldedOnlyOnceConnectedHereAndConfirmedByTheLowerNode() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        peerRuns("dev_a");

        // e.g. this node is the target of a failover whose connect is still running
        driverService.connecting.add("dev_a");
        driverService.checkDuplicatedDevices();
        assertTrue(protocol.requested.isEmpty());

        // connected, but the replica of node 0 is stale: that node no longer runs dev_a
        driverService.connecting.clear();
        peerDeviceStatus.clear();
        driverService.checkDuplicatedDevices();
        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());

        peerRuns("dev_a");
        driverService.checkDuplicatedDevices();
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
        assertTrue(driverService.driverProtocols.isEmpty());
        assertTrue(driverStarter.addedDevices.isEmpty());
    }

    @Test
    void duplicateIsKeptWhenTheLowerNodeDoesNotConfirmWithinTheProbeTimeout() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        peerRuns("dev_a");
        // two heartbeat intervals, at least 1 s; node 0 answers only after 10 s
        assertEquals(Duration.ofSeconds(1), driverService.probeTimeout);
        var gate = new CountDownLatch(1);
        peerDeviceStatusGate = gate;

        var started = System.nanoTime();
        driverService.checkDuplicatedDevices();
        var elapsed = System.nanoTime() - started;

        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5), "returned after " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
        assertTrue(peerDeviceStatusRequests.get() >= 1);
        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());

        // node 0 is asked again at once, on a task of its own with twice the probe timeout, and dev_a is yielded once
        // it confirms
        assertEquals(Duration.ofSeconds(2), driverService.slowConfirmationBudget(0));
        awaitCondition(() -> peerDeviceStatusRequests.get() == 2, 5);
        peerDeviceStatusGate = null;
        gate.countDown();
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(2, peerDeviceStatusRequests.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
    }

    @Test
    void connectAllMarksItsDevicesAsConnectingUntilConnected() {
        var markedWhileConnecting = new CopyOnWriteArrayList<Boolean>();
        driverStarter.statusListener = status -> {
            if (status.getStatus() == StatusCode.CONNECTING)
                markedWhileConnecting.add(driverService.connecting.contains(status.getDeviceId()));
        };

        driverService.connectAll(Set.of(dummyDevice("dev_a"), device("dev_b")));

        assertEquals(List.of(true), markedWhileConnecting);
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
        assertTrue(driverService.connecting.isEmpty());
        assertTrue(driverService.building.isEmpty());
    }

    @Test
    void leadershipChangesRunTheDuplicateCheck() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var first = register("dev_a");
        var second = register("dev_b");
        setDeviceIds(SELF, "dev_a", "dev_b");
        addMember(0);
        peerRuns("dev_a", "dev_b");

        // e.g. both transient leaders failed dev_a over
        setDeviceIds(0, "dev_a");
        assertReturnsAtOnce(becomeFollowerHandler());
        awaitCondition(() -> !driverService.driverProtocols.containsKey("dev_a"), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), first.requested);
        assertTrue(second.requested.isEmpty());

        setDeviceIds(0, "dev_a", "dev_b");
        assertReturnsAtOnce(splitBrainResolvedHandler());
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), second.requested);
        assertTrue(driverStarter.addedDevices.isEmpty());
    }

    @Test
    void membershipChangeSchedulesTheDuplicateCheckInsteadOfSleepingOnTheEventThread() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        peerRuns("dev_a");

        var handler = clusterAddedHandler();
        assertReturnsAtOnce(() -> handler.accept(0));
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
    }

    @Test
    void concurrentDuplicateCheckRequestsCoalesceIntoOneMoreRun() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        // held at the gate for longer than the probe timeout would allow
        driverService.probeTimeout = Duration.ofSeconds(30);
        // node 0 does not confirm dev_a: every check asks it, and keeps the device
        var gate = new CountDownLatch(1);
        peerDeviceStatusGate = gate;
        var first = new Thread(driverService::checkDuplicatedDevices);
        first.setDaemon(true);
        first.start();
        awaitCondition(() -> peerDeviceStatusRequests.get() == 1, 10);

        // what a thaw fires meanwhile: the delayed checks of a join and of the leadership changes, replaced replicas and
        // the periodic check. None waits for the running check, and none runs a check of its own
        clusterAddedHandler().accept(0);
        becomeFollowerHandler().run();
        splitBrainResolvedHandler().run();
        var delayed = (CompositeDisposable) field(DriverService.class, "delayedDuplicateChecks").get(driverService);
        var triggers = new ArrayList<Thread>();
        for (int i = 0; i < 5; i++) {
            var trigger = i == 0
                    ? new Thread(driverService::checkDuplicatedDevices)
                    : new Thread(() -> {
                        try {
                            overwrittenHandler().accept(0);
                        } catch (Throwable e) {
                            throw new RuntimeException(e);
                        }
                    });
            trigger.setDaemon(true);
            triggers.add(trigger);
        }
        triggers.forEach(Thread::start);
        for (var trigger : triggers) {
            trigger.join(5_000);
            assertFalse(trigger.isAlive());
        }
        awaitCondition(() -> delayed.size() == 0, 10);
        assertTrue(first.isAlive());
        assertEquals(1, peerDeviceStatusRequests.get());

        gate.countDown();
        first.join(10_000);
        assertFalse(first.isAlive());
        // exactly one more check ran, on the thread of the first one
        assertEquals(2, peerDeviceStatusRequests.get());
        Thread.sleep(2L * HEARTBEAT_MILLIS);
        assertEquals(2, peerDeviceStatusRequests.get());
        assertTrue(protocol.requested.isEmpty());

        // with nothing requested meanwhile, a check runs once
        driverService.checkDuplicatedDevices();
        assertEquals(3, peerDeviceStatusRequests.get());
    }

    @Test
    void requestDuringADuplicateCheckRunsItOnceMoreWithTheChangedReplicas() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var kept = register("dev_a");
        var yielded = register("dev_b");
        setDeviceIds(SELF, "dev_a", "dev_b");
        setDeviceIds(0, "dev_a");
        addMember(0);
        // held at the gate for longer than the probe timeout would allow
        driverService.probeTimeout = Duration.ofSeconds(30);
        var gate = new CountDownLatch(1);
        peerDeviceStatusGate = gate;
        var first = new Thread(driverService::checkDuplicatedDevices);
        first.setDaemon(true);
        first.start();
        awaitCondition(() -> peerDeviceStatusRequests.get() == 1, 10);

        // node 0 gains dev_b after the running check has read the replicas
        setDeviceIds(0, "dev_a", "dev_b");
        peerRuns("dev_b");
        var overwritten = overwrittenHandler();
        assertReturnsAtOnce(() -> overwritten.accept(0));
        assertEquals(1, peerDeviceStatusRequests.get());
        gate.countDown();
        first.join(10_000);

        assertFalse(first.isAlive());
        assertEquals(2, peerDeviceStatusRequests.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), yielded.requested);
        assertTrue(kept.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
        assertEquals(List.of(Map.of("dev_b", "disconnected")), driverStarter.deletedDevices);
    }

    @Test
    void periodicCheckContinuesAfterACheckThrows() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        peerRuns("dev_a");

        // a failing check leaves nothing behind that would keep the next one from running
        driverStarter.deviceIdMapFailures.set(1);
        driverService.checkDuplicatedDevices();
        assertEquals(0, driverStarter.deviceIdMapFailures.get());
        assertEquals(0, peerDeviceStatusRequests.get());

        // the first two periodic checks throw
        driverStarter.deviceIdMapFailures.set(2);
        driverService.start();
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 15);
        assertEquals(0, driverStarter.deviceIdMapFailures.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
    }

    @Test
    void delayedDuplicateCheckIsCancelledOnDispose() throws Throwable {
        setCluster(Position.FOLLOWER, true);
        becomeFollowerHandler().run();
        driverService.dispose();

        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        peerRuns("dev_a");
        Thread.sleep(4L * HEARTBEAT_MILLIS);

        assertEquals(0, peerDeviceStatusRequests.get());
        assertTrue(protocol.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());
    }

    @Test
    void inactivationRemembersTheCurrentSettingWithTheDataWrittenSinceTheConnect() throws Throwable {
        setCluster(Position.LEADER, false);
        var connected = dummyDevice("dev_a");
        connected.setData(new HashMap<>(Map.of("initial", 1)));
        register(connected);
        var fallback = register(dummyDevice("dev_b"));
        // this node's own object: dev_a with the data its scripts wrote since, dev_b with an entry that is no valid Device
        var current = setting(connected);
        current.put("data", new HashMap<>(Map.of("initial", 1, "written", 2)));
        var invalid = setting(dummyDevice("dev_b"));
        invalid.put("socketTimeout", "not a number");
        setObject(SELF, Map.of("dev_a", current, "dev_b", invalid));

        inactivatedHandler().run();
        assertTrue(driverService.driverProtocols.isEmpty());

        setDeviceIds(SELF);
        setCluster(Position.LEADER, true);
        activatedHandler().run();

        assertEquals(1, driverStarter.addedDevices.size());
        var reconnected = driverStarter.addedDevices.getFirst();
        assertEquals(Map.of("initial", 1, "written", 2), reconnected.get("dev_a").getData());
        assertSame(fallback.device, reconnected.get("dev_b"));
        assertEquals(Set.of("dev_a", "dev_b"), driverService.driverProtocols.keySet());
        assertEquals(Map.of("initial", 1, "written", 2), driverService.driverProtocols.get("dev_a").device.getData());
    }

    @Test
    void inactivationRemembersExactlyTheDevicesItDisconnects() throws Throwable {
        setCluster(Position.LEADER, false);
        var first = register(dummyDevice("dev_a"));
        var stuck = register(dummyDevice("dev_c"));
        stuck.changeStatusResult = "disconnect failed::stuck";
        setDevices(SELF, first.device, stuck.device);
        var mutex = field(DriverService.class, "driverMutex").get(driverService);
        var handler = new Thread(() -> {
            try {
                inactivatedHandler().run();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
        handler.setDaemon(true);
        StubProtocol connectedMeanwhile;
        synchronized (mutex) {
            handler.start();
            awaitBlockedOn(handler, mutex);
            // e.g. a connect-all to this node, or a failover that passed its quorum check just before the inactivation
            connectedMeanwhile = register(dummyDevice("dev_b"));
            setDevices(SELF, first.device, connectedMeanwhile.device, stuck.device);
        }
        handler.join(10_000);

        assertFalse(handler.isAlive());
        assertEquals(List.of(StatusCode.DISCONNECTED), first.requested);
        assertEquals(List.of(StatusCode.DISCONNECTED), connectedMeanwhile.requested);
        // the device that could not be disconnected keeps running here, so it is not reconnected on activation
        assertEquals(Set.of("dev_c"), driverService.driverProtocols.keySet());
        assertEquals(Set.of("dev_a", "dev_b"), inactivationDropped().keySet());
    }

    @Test
    void activationKeepsTheDevicesWithoutAResultForTheNextActivation() throws Throwable {
        setCluster(Position.FOLLOWER, false);
        var first = register(dummyDevice("dev_a"));
        var second = register(dummyDevice("dev_b"));
        setDevices(SELF, first.device, second.device);
        inactivatedHandler().run();
        assertEquals(Set.of("dev_a", "dev_b"), inactivationDropped().keySet());
        setDeviceIds(SELF);

        // the connect throws (a deterministic 4xx from the leader): nothing is lost
        forwardedStatus = 400;
        setCluster(Position.FOLLOWER, true);
        var handler = activatedHandler();
        assertThrows(RuntimeException.class, handler::run);
        assertEquals(Set.of("dev_a", "dev_b"), inactivationDropped().keySet());

        // the leader answers for dev_a only
        forwardedStatus = 200;
        activatedHandler().run();
        assertEquals(Set.of("dev_b"), inactivationDropped().keySet());

        // the next activation retries dev_b only
        activatedHandler().run();
        assertEquals(3, forwardedBodies.size());
        assertEquals(Set.of("dev_b"), Set.copyOf(json.readTree(forwardedBodies.getLast()).findValuesAsText("id")));
        assertEquals(Set.of("dev_b"), inactivationDropped().keySet());
    }

    private void setCluster(Position position, boolean activated) throws Exception {
        field(ClusterStarter.class, "position").set(clusterStarter, position);
        field(ClusterStarter.class, "isActivated").setBoolean(clusterStarter, activated);
    }

    /** replaces the replica of nodeIndex, as held by this node, with registrations of deviceIds */
    private void setDeviceIds(int nodeIndex, String... deviceIds) throws Exception {
        setDevices(nodeIndex, Arrays.stream(deviceIds).map(DriverServiceClusterTest::device).toArray(Device[]::new));
    }

    /** replaces the replica of nodeIndex, as held by this node, with registrations of devices */
    private void setDevices(int nodeIndex, Device... devices) throws Exception {
        var object = new HashMap<String, Object>();
        for (var device : devices)
            object.put(device.getId(), setting(device));
        setObject(nodeIndex, object);
    }

    /** replaces the replica of nodeIndex, as held by this node, with object */
    private void setObject(int nodeIndex, Map<String, Object> object) throws Exception {
        replicas().put(nodeIndex, new HashMap<>(object));
        replicaSequences().put(nodeIndex, 1L);
    }

    /** the fake node (index 0) reports to run deviceIds */
    private void peerRuns(String... deviceIds) {
        for (var deviceId : deviceIds)
            peerDeviceStatus.put(deviceId, StatusCode.CONNECTED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Device> inactivationDropped() throws Exception {
        var dropped = (Map<String, Device>) field(DriverService.class, "inactivationDropped").get(driverService);
        synchronized (dropped) {
            return new HashMap<>(dropped);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Map<String, Object>> replicas() throws Exception {
        return (Map<Integer, Map<String, Object>>) clusterServiceField("sharedObject");
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Long> replicaSequences() throws Exception {
        return (Map<Integer, Long>) clusterServiceField("sharedObjectSeq");
    }

    /** makes nodeIndex a member, as if its heartbeats were received */
    @SuppressWarnings("unchecked")
    private void addMember(int nodeIndex) throws Exception {
        ((Map<Integer, Disposable>) clusterServiceField("nodes")).put(nodeIndex, Disposable.empty());
    }

    private Object clusterServiceField(String name) throws Exception {
        var clusterService = field(ClusterStarter.class, "clusterService").get(clusterStarter);
        return field(clusterService.getClass(), name).get(clusterService);
    }

    private StubProtocol register(String deviceId) {
        return register(device(deviceId));
    }

    private StubProtocol register(Device device) {
        var protocol = new StubProtocol(device);
        driverService.driverProtocols.put(device.getId(), protocol);
        return protocol;
    }

    /** an unknown protocol, so a connect attempt fails before starting any transport */
    private static Device device(String id) {
        var device = new Device();
        device.setId(id);
        device.setConnectionUrl("unknown://");
        return device;
    }

    /** connects without a transport; its first command would run only after the default initial delay */
    private static Device dummyDevice(String id) {
        var device = device(id);
        device.setConnectionUrl("dummy://");
        return device;
    }

    private Map<String, Object> setting(Device device) {
        return json.convertValue(device, new TypeReference<HashMap<String, Object>>() {});
    }

    private Map<String, Object> sharedObjectOf(Device device) {
        return Map.of(device.getId(), setting(device));
    }

    @SuppressWarnings("unchecked")
    private BiConsumer<Integer, Map<String, Object>> clusterDeletedHandler() throws Exception {
        var events = (List<Pair<String, BiConsumer<Integer, Map<String, Object>>>>)
                field(ClusterEvents.class, "clusterDeletedEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    @SuppressWarnings("unchecked")
    private Consumer<Integer> overwrittenHandler() throws Exception {
        var events = (List<Pair<String, Consumer<Integer>>>)
                field(ClusterEvents.class, "overwrittenEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    @SuppressWarnings("unchecked")
    private Consumer<Integer> clusterAddedHandler() throws Exception {
        var events = (List<Pair<String, Consumer<Integer>>>)
                field(ClusterEvents.class, "clusterAddedEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    @SuppressWarnings("unchecked")
    private Action becomeFollowerHandler() throws Exception {
        var events = (List<Pair<String, Action>>) field(ClusterEvents.class, "becomeFollowerEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    @SuppressWarnings("unchecked")
    private Action splitBrainResolvedHandler() throws Exception {
        var events = (List<Pair<String, Action>>) field(ClusterEvents.class, "splitBrainResolvedEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    @SuppressWarnings("unchecked")
    private Action inactivatedHandler() throws Exception {
        var events = (List<Pair<String, Action>>) field(ClusterEvents.class, "inactivatedEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    @SuppressWarnings("unchecked")
    private Action activatedHandler() throws Exception {
        var events = (List<Pair<String, Action>>) field(ClusterEvents.class, "activatedEvents").get(driverService.clusterEvents());
        return events.getFirst().getValue1();
    }

    private static void awaitBlockedOn(Thread thread, Object monitor) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor))
                return;
            Thread.sleep(5);
        }
        fail("caller never blocked on the connect-all mutex");
    }

    /** returns sooner than the two heartbeat intervals a handler used to sleep before its duplicate check */
    private static void assertReturnsAtOnce(Action handler) throws Throwable {
        var started = System.nanoTime();
        handler.run();
        var elapsed = System.nanoTime() - started;
        assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(2L * HEARTBEAT_MILLIS),
                "returned after " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
    }

    private static void awaitCondition(BooleanSupplier condition, int timeoutSeconds) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline)
                fail("condition not met within " + timeoutSeconds + " s");
            Thread.sleep(20);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
