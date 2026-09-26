package com.sds.communicators.driver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.cluster.ClusterStarter;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.type.Position;
import com.sds.communicators.common.type.StatusCode;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The confirmations a duplicate check of {@link DriverService} asks the lower nodes for, on one real but never started
 * node (index {@value SELF}) with two fake lower nodes (0 and 1). Each reports to run the devices a test sets
 * (device-status), and can hold that answer; the replicas held by this node are set directly.
 */
class DuplicateConfirmationTest {
    private static final int HEARTBEAT_MILLIS = 200;
    private static final int SELF = 2;

    private final ObjectMapper json = new ObjectMapper();
    private final List<FakeNode> fakeNodes = new ArrayList<>();
    private DriverServiceClusterTest.TestDriverStarter driverStarter;
    private ClusterStarter clusterStarter;
    private DriverService driverService;

    /** a lower node that answers the node lookup with its index and device-status with the devices it runs */
    private class FakeNode {
        final Map<String, StatusCode> running = new ConcurrentHashMap<>();
        /** device-status requests received, and answered */
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger answers = new AtomicInteger();
        /** when set, device-status is answered only once it is opened (at most 10 s) */
        volatile CountDownLatch gate = null;
        /** how long each device-status request is answered after it arrived */
        volatile long delayMillis = 0;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final HttpServer server;

        FakeNode(int index) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var path = exchange.getRequestURI().getPath();
                String body;
                int status = 200;
                if (path.equals("/index")) {
                    body = Integer.toString(index);
                } else if (path.equals("/cluster/internal/node-status")) {
                    body = "{\"nodeIndex\":" + index + ",\"position\":\"FOLLOWER\"}";
                } else if (path.equals("/driver/device-status")) {
                    // read before the request counts, so a test that sees it counted also sees how it is answered
                    var held = gate;
                    var delay = delayMillis;
                    requests.incrementAndGet();
                    try {
                        if (held != null)
                            held.await(10, TimeUnit.SECONDS);
                        if (delay > 0)
                            Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    answers.incrementAndGet();
                    body = json.writeValueAsString(running);
                } else {
                    body = "not found";
                    status = 404;
                }
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            // concurrent requests are served concurrently, so one held at the gate does not delay the others
            server.setExecutor(executor);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void runs(String... deviceIds) {
            for (var deviceId : deviceIds)
                running.put(deviceId, StatusCode.CONNECTED);
        }

        /** holds device-status answers from now on, until the returned gate is opened */
        CountDownLatch hold() {
            var held = new CountDownLatch(1);
            gate = held;
            return held;
        }

        void release() {
            var held = gate;
            gate = null;
            if (held != null)
                held.countDown();
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        fakeNodes.add(new FakeNode(0));
        fakeNodes.add(new FakeNode(1));
        int selfPort = freePort();
        var targetUrls = new HashSet<String>();
        targetUrls.add("http://127.0.0.1:" + selfPort);
        fakeNodes.forEach(node -> targetUrls.add(node.url()));
        driverStarter = new DriverServiceClusterTest.TestDriverStarter(ClusterStarter.builder(targetUrls, selfPort, SELF)
                .setHeartbeatSendingIntervalMillis(HEARTBEAT_MILLIS));
        clusterStarter = driverStarter.getClusterStarter();
        driverService = driverServiceOf(driverStarter);
        field(ClusterStarter.class, "position").set(clusterStarter, Position.FOLLOWER);
        field(ClusterStarter.class, "isActivated").setBoolean(clusterStarter, true);
    }

    @AfterEach
    void tearDown() {
        fakeNodes.forEach(FakeNode::release);
        driverService.driverProtocols.clear();
        driverService.dispose();
        clusterStarter.dispose();
        fakeNodes.forEach(FakeNode::stop);
    }

    @Test
    void aSlowLowerNodeDoesNotDelayTheYieldToAnotherLowerNode() throws Throwable {
        var slow = fakeNodes.get(0);
        var fast = fakeNodes.get(1);
        var withSlow = register("dev_a");
        var withFast = register("dev_b");
        setDeviceIds(SELF, "dev_a", "dev_b");
        setDeviceIds(0, "dev_a");
        setDeviceIds(1, "dev_b");
        addMember(0);
        addMember(1);
        slow.runs("dev_a");
        fast.runs("dev_b");
        // node 0 answers only once released, which here the probe timeout does not cut short
        driverService.probeTimeout = Duration.ofSeconds(30);
        var gate = slow.hold();
        var check = new Thread(driverService::checkDuplicatedDevices);
        check.setDaemon(true);
        check.start();

        // node 1 confirms dev_b, which is yielded while node 0 still holds its answer
        awaitCondition(() -> !driverService.driverProtocols.containsKey("dev_b"), 5);
        assertEquals(List.of(StatusCode.DISCONNECTED), withFast.requested);
        assertTrue(check.isAlive());
        assertEquals(1, slow.requests.get());
        assertEquals(0, slow.answers.get());
        assertTrue(withSlow.requested.isEmpty());

        gate.countDown();
        check.join(10_000);
        assertFalse(check.isAlive());
        assertEquals(List.of(StatusCode.DISCONNECTED), withSlow.requested);
        assertTrue(driverService.driverProtocols.isEmpty());
        assertEquals(List.of(Map.of("dev_b", "disconnected"), Map.of("dev_a", "disconnected")), driverStarter.deletedDevices);
    }

    @Test
    void aLowerNodeThatDoesNotConfirmWithinTheProbeTimeoutKeepsItsDuplicateForTheNextRun() throws Throwable {
        var slow = fakeNodes.get(0);
        var fast = fakeNodes.get(1);
        var withSlow = register("dev_a");
        var withFast = register("dev_b");
        setDeviceIds(SELF, "dev_a", "dev_b");
        setDeviceIds(0, "dev_a");
        setDeviceIds(1, "dev_b");
        addMember(0);
        addMember(1);
        slow.runs("dev_a");
        fast.runs("dev_b");
        // node 0 answers only after 10 s, far beyond the probe timeout
        slow.hold();

        var started = System.nanoTime();
        driverService.checkDuplicatedDevices();
        var elapsed = System.nanoTime() - started;

        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5), "returned after " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
        assertEquals(List.of(StatusCode.DISCONNECTED), withFast.requested);
        assertEquals(0, slow.answers.get());
        assertTrue(withSlow.requested.isEmpty());
        assertEquals(Set.of("dev_a"), driverService.driverProtocols.keySet());

        // node 0 is asked again at once, on a task of its own with twice the probe timeout; once it confirms, the check
        // runs again and yields dev_a
        assertEquals(driverService.probeTimeout.multipliedBy(2), driverService.slowConfirmationBudget(0));
        slow.release();
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(2, slow.requests.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), withSlow.requested);
        assertEquals(1, fast.requests.get());
    }

    @Test
    void aLowerNodeThatAnswersInTwoToThreeProbeTimeoutsConfirmsOnItsOwnAndItsDuplicateIsYielded() throws Throwable {
        var slow = fakeNodes.get(0);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        slow.runs("dev_a");
        var probe = Duration.ofMillis(500);
        driverService.probeTimeout = probe;
        // it stays slow: every answer comes 2.8 probe timeouts after the request
        slow.delayMillis = 1400;

        var started = System.nanoTime();
        driverService.checkDuplicatedDevices();
        var elapsed = System.nanoTime() - started;

        // the check waited the probe timeout only, and keeps dev_a meanwhile
        assertTrue(elapsed < probe.multipliedBy(3).toNanos() / 2, "returned after " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
        assertTrue(protocol.requested.isEmpty());
        assertEquals(probe.multipliedBy(2), driverService.slowConfirmationBudget(0));

        // asked on its own within 2 and then 4 probe timeouts, it confirms the second time, and dev_a is yielded
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
        assertEquals(List.of(Map.of("dev_a", "disconnected")), driverStarter.deletedDevices);
        assertEquals(3, slow.requests.get());
        // it answered, but later than the probe timeout: its next confirmation still runs on its own, within that budget
        assertEquals(probe.multipliedBy(4), driverService.slowConfirmationBudget(0));

        // with the duplicate gone, the next run forgets it
        driverService.checkDuplicatedDevices();
        assertNull(driverService.slowConfirmationBudget(0));
        assertEquals(3, slow.requests.get());
    }

    @Test
    void aSlowLowerNodeThatAnswersWithinTheProbeTimeoutAgainIsAskedByTheCheckItselfAgain() throws Throwable {
        var node = fakeNodes.get(0);
        var first = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        node.runs("dev_a");
        var probe = driverService.probeTimeout;
        var gate = node.hold();
        driverService.checkDuplicatedDevices();
        assertEquals(probe.multipliedBy(2), driverService.slowConfirmationBudget(0));
        awaitCondition(() -> node.requests.get() == 2, 5);

        // the confirmation on its own answers at once, and dev_a is yielded
        node.release();
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), first.requested);
        assertNull(driverService.slowConfirmationBudget(0));

        // the budget is reset: the check asks the node itself again, and yields a new duplicate at once
        var second = register("dev_b");
        setDeviceIds(SELF, "dev_b");
        setDeviceIds(0, "dev_a", "dev_b");
        node.runs("dev_b");
        driverService.checkDuplicatedDevices();
        assertEquals(3, node.requests.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), second.requested);
        assertTrue(driverService.driverProtocols.isEmpty());

        // and when it is slow again, the check waits the probe timeout, after which it is asked on its own with twice that
        var third = register("dev_c");
        setDeviceIds(SELF, "dev_c");
        setDeviceIds(0, "dev_a", "dev_b", "dev_c");
        node.runs("dev_c");
        gate = node.hold();
        var started = System.nanoTime();
        driverService.checkDuplicatedDevices();
        var elapsed = System.nanoTime() - started;
        assertTrue(elapsed >= probe.toNanos() * 9 / 10 && elapsed < probe.multipliedBy(2).toNanos(),
                "returned after " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
        assertEquals(probe.multipliedBy(2), driverService.slowConfirmationBudget(0));
        assertTrue(third.requested.isEmpty());
        gate.countDown();
        awaitCondition(() -> driverService.driverProtocols.isEmpty(), 10);
        assertEquals(List.of(StatusCode.DISCONNECTED), third.requested);
    }

    @Test
    void theCheckIsHeldAboutOneProbeTimeoutWhileSlowLowerNodesAreAskedOnTheirOwn() throws Throwable {
        var protocols = List.of(register("dev_a"), register("dev_b"));
        setDeviceIds(SELF, "dev_a", "dev_b");
        setDeviceIds(0, "dev_a");
        setDeviceIds(1, "dev_b");
        addMember(0);
        addMember(1);
        fakeNodes.get(0).runs("dev_a");
        fakeNodes.get(1).runs("dev_b");
        var probe = Duration.ofMillis(500);
        driverService.probeTimeout = probe;
        // both stay slow: every answer comes 2.8 probe timeouts after the request
        fakeNodes.forEach(node -> node.delayMillis = 1400);

        // what the periodic check and the events run meanwhile, one run after another
        var durations = new ArrayList<Long>();
        var first = System.nanoTime();
        var deadline = first + TimeUnit.SECONDS.toNanos(15);
        while (!driverService.driverProtocols.isEmpty() && System.nanoTime() < deadline) {
            var started = System.nanoTime();
            driverService.checkDuplicatedDevices();
            durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            Thread.sleep(50);
        }
        var total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first);

        assertTrue(driverService.driverProtocols.isEmpty(), "not yielded, runs took " + durations + " ms");
        protocols.forEach(protocol -> assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested));
        // no run waited for the longer budgets (2 and 4 probe timeouts) the nodes are asked with on their own
        assertTrue(durations.size() > 5, "runs took " + durations + " ms");
        assertTrue(durations.stream().allMatch(millis -> millis < probe.multipliedBy(3).toMillis() / 2), "runs took " + durations + " ms");
        // each node confirmed the third time it was asked (by the check, then on its own within 2 and 4 probe timeouts),
        // and was probed meanwhile, at most once per probe timeout
        fakeNodes.forEach(node -> assertTrue(node.requests.get() >= 3 && node.requests.get() <= 3 + total / probe.toMillis(),
                node.requests.get() + " requests in " + total + " ms"));
    }

    @Test
    void aLowerNodeThatStaysSlowIsProbedWhileItsConfirmationRunsAndStillConfirmsOnItsOwn() throws Throwable {
        var node = fakeNodes.get(0);
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        node.runs("dev_a");
        var probe = Duration.ofMillis(500);
        driverService.probeTimeout = probe;
        // it stays slow: every answer, to a probe as well, comes 2.8 probe timeouts after the request
        node.delayMillis = 1400;

        // what the periodic check runs meanwhile, one run after another
        var durations = new ArrayList<Long>();
        var first = System.nanoTime();
        var deadline = first + TimeUnit.SECONDS.toNanos(10);
        while (!driverService.driverProtocols.isEmpty() && System.nanoTime() < deadline) {
            var started = System.nanoTime();
            driverService.checkDuplicatedDevices();
            durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            Thread.sleep(100);
        }
        var total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first);

        // it confirmed on its own within 4 probe timeouts, as without the probes
        assertTrue(driverService.driverProtocols.isEmpty(), "not yielded, runs took " + durations + " ms");
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
        // only the first run asked it itself; no later one waited for it
        assertTrue(durations.get(0) >= probe.toMillis() * 9 / 10, "runs took " + durations + " ms");
        assertTrue(durations.stream().skip(1).allMatch(millis -> millis < probe.toMillis()), "runs took " + durations + " ms");
        // besides those three confirmations it was probed, at most once per probe timeout
        assertTrue(node.requests.get() > 3 && node.requests.get() <= 3 + total / probe.toMillis(), node.requests.get() + " requests in " + total + " ms");
        // the probes that did not answer changed nothing: the late answer kept the budget of the one that answered
        assertEquals(probe.multipliedBy(4), driverService.slowConfirmationBudget(0));
    }

    @Test
    void aLowerNodeThatRecoversWhileItsConfirmationIsStuckIsConfirmedByAProbeWithinAboutOneCheckInterval() throws Throwable {
        var node = fakeNodes.get(0);
        var first = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        addMember(0);
        node.runs("dev_a");
        var probe = Duration.ofMillis(500);
        driverService.probeTimeout = probe;
        // slow: each request is answered 7 probe timeouts after it arrived
        node.delayMillis = probe.multipliedBy(7).toMillis();

        // asked by the check, then on its own within 2 and 4 probe timeouts, which all run out: now asked within 8
        driverService.checkDuplicatedDevices();
        awaitCondition(() -> probe.multipliedBy(8).equals(driverService.slowConfirmationBudget(0)) && node.requests.get() == 4, 10);

        // it recovers, but that request stays stuck in it: answered 7 probe timeouts after it arrived, within its budget
        node.delayMillis = 0;
        var recovered = System.nanoTime();
        // what the periodic check runs meanwhile; once that confirmation has run a probe timeout, a run probes the node
        var durations = new ArrayList<Long>();
        while (!driverService.driverProtocols.isEmpty() && System.nanoTime() - recovered < TimeUnit.SECONDS.toNanos(10)) {
            var started = System.nanoTime();
            driverService.checkDuplicatedDevices();
            durations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            Thread.sleep(100);
        }
        var yieldedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recovered);

        // confirmed to the probe and yielded within about a probe timeout and a check interval, not once the stuck
        // request answers (7 probe timeouts after it arrived); no run waited for the probe
        assertTrue(driverService.driverProtocols.isEmpty(), "not yielded, runs took " + durations + " ms");
        assertEquals(List.of(StatusCode.DISCONNECTED), first.requested);
        assertTrue(yieldedAfter < probe.multipliedBy(4).toMillis(), "yielded " + yieldedAfter + " ms after it recovered");
        assertTrue(durations.stream().allMatch(millis -> millis < probe.toMillis()), "runs took " + durations + " ms");
        assertEquals(5, node.requests.get());
        // the fast answer reset the budget
        assertNull(driverService.slowConfirmationBudget(0));

        // the stuck request was cancelled: neither its late answer nor the end of its budget changes anything
        var stuckEnds = recovered + probe.multipliedBy(9).toNanos();
        while (System.nanoTime() < stuckEnds) {
            driverService.checkDuplicatedDevices();
            Thread.sleep(100);
        }
        assertNull(driverService.slowConfirmationBudget(0));
        assertEquals(5, node.requests.get());

        // and the check asks the node itself again: a new duplicate is yielded at once
        var second = register("dev_b");
        setDeviceIds(SELF, "dev_b");
        setDeviceIds(0, "dev_a", "dev_b");
        node.runs("dev_b");
        driverService.checkDuplicatedDevices();
        assertEquals(6, node.requests.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), second.requested);
        assertTrue(driverService.driverProtocols.isEmpty());
        assertNull(driverService.slowConfirmationBudget(0));
    }

    @Test
    void aDeviceDuplicatedWithTwoLowerNodesIsYieldedOnce() throws Throwable {
        var protocol = register("dev_a");
        setDeviceIds(SELF, "dev_a");
        setDeviceIds(0, "dev_a");
        setDeviceIds(1, "dev_a");
        addMember(0);
        addMember(1);
        fakeNodes.forEach(node -> node.runs("dev_a"));

        driverService.checkDuplicatedDevices();

        // both confirm it, and whichever answers second finds it yielded already
        assertEquals(1, fakeNodes.get(0).requests.get());
        assertEquals(1, fakeNodes.get(1).requests.get());
        assertEquals(List.of(StatusCode.DISCONNECTED), protocol.requested);
        assertEquals(List.of(Map.of("dev_a", "disconnected")), driverStarter.deletedDevices);
        assertTrue(driverService.driverProtocols.isEmpty());
    }

    @Test
    void probeTimeoutIsTwoHeartbeatIntervalsAtLeastOneSecondAndNeverLongerThanTheReadTimeout() throws Exception {
        assertEquals(Duration.ofSeconds(1), driverService.probeTimeout);
        assertEquals(Duration.ofMillis(3000), probeTimeoutOf(1500, null));
        assertEquals(Duration.ofMillis(2500), probeTimeoutOf(1500, 2500));
        assertEquals(Duration.ofMillis(700), probeTimeoutOf(200, 700));
    }

    private Duration probeTimeoutOf(int heartbeatMillis, Integer readTimeoutMillis) throws Exception {
        int port = freePort();
        var builder = ClusterStarter.builder(Set.of("http://127.0.0.1:" + port), port, SELF)
                .setHeartbeatSendingIntervalMillis(heartbeatMillis);
        if (readTimeoutMillis != null)
            builder.setReadTimeoutMillis(readTimeoutMillis);
        var starter = new DriverServiceClusterTest.TestDriverStarter(builder);
        try {
            return driverServiceOf(starter).probeTimeout;
        } finally {
            starter.getClusterStarter().dispose();
        }
    }

    /** replaces the replica of nodeIndex, as held by this node, with registrations of deviceIds */
    @SuppressWarnings("unchecked")
    private void setDeviceIds(int nodeIndex, String... deviceIds) throws Exception {
        var object = new HashMap<String, Object>();
        for (var deviceId : deviceIds)
            object.put(deviceId, json.convertValue(device(deviceId), new TypeReference<HashMap<String, Object>>() {}));
        ((Map<Integer, Map<String, Object>>) clusterServiceField("sharedObject")).put(nodeIndex, object);
        ((Map<Integer, Long>) clusterServiceField("sharedObjectSeq")).put(nodeIndex, 1L);
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

    private DriverServiceClusterTest.StubProtocol register(String deviceId) {
        var protocol = new DriverServiceClusterTest.StubProtocol(device(deviceId));
        driverService.driverProtocols.put(deviceId, protocol);
        return protocol;
    }

    /** an unknown protocol, so a connect attempt fails before starting any transport */
    private static Device device(String id) {
        var device = new Device();
        device.setId(id);
        device.setConnectionUrl("unknown://");
        return device;
    }

    private static DriverService driverServiceOf(DriverStarter driverStarter) throws Exception {
        return (DriverService) field(DriverStarter.class, "driverService").get(driverStarter);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
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
