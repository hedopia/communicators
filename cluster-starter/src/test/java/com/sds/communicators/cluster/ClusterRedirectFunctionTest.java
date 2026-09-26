package com.sds.communicators.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Retry policy of to-leader-function (no retry of deterministic failures, stop on interrupt) and the
 * first-match leader/index lookups. The node is built but not started: only its redirect function is used.
 */
class ClusterRedirectFunctionTest {
    private static StubServer stub;
    private ClusterStarter starter;

    @BeforeAll
    static void startStub() throws Exception {
        var json = new ObjectMapper();
        stub = new StubServer()
                .respond("/ok", 200, "")
                .respond("/bad-request", 400, "rejected")
                .respond("/unavailable", 503, "busy")
                .respond("/leader", 200, json.writeValueAsString(new NodeStatus(2, Position.LEADER, true)))
                .respond("/follower", 200, json.writeValueAsString(new NodeStatus(3, Position.FOLLOWER, true)))
                .respond("/pooled-leader", 200, json.writeValueAsString(new NodeStatus(2, Position.LEADER, true)))
                .respond("/slow-follower", 30, 200, json.writeValueAsString(new NodeStatus(3, Position.FOLLOWER, true)))
                .hang("/hung");
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @BeforeEach
    void buildNode() throws Exception {
        int port = StubServer.freePort();
        // default heartbeat interval (2 s): status probes time out after 4 s
        starter = ClusterStarter.builder(Set.of("http://127.0.0.1:" + port), port, 1).build();
    }

    @AfterEach
    void disposeNode() {
        starter.dispose();
    }

    private void call(String path, Object body) {
        starter.getNodeHttpClient().call(stub.url(path), "POST", body, null);
    }

    @Test
    void confirmedRethrowsClientErrorWithoutRetry() {
        starter.position = Position.LEADER;
        starter.heartbeatSendingIntervalMillis = 50;
        var attempts = new AtomicInteger();
        var e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(NodeHttpClient.CallException.class, () ->
                starter.toLeaderFuncConfirmed(url -> {
                    attempts.incrementAndGet();
                    call("/bad-request", null);
                }, "test")));
        assertEquals(400, e.getStatusCode());
        assertEquals(1, attempts.get());
    }

    @Test
    void confirmedRethrowsUnencodableRequestWithoutRetry() {
        starter.position = Position.LEADER;
        starter.heartbeatSendingIntervalMillis = 50;
        var attempts = new AtomicInteger();
        var e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(NodeHttpClient.CallException.class, () ->
                starter.toLeaderFuncConfirmed(url -> {
                    attempts.incrementAndGet();
                    call("/ok", new Object());
                }, "test")));
        assertTrue(e.isRequestEncodingFailure());
        assertEquals(1, attempts.get());
    }

    @Test
    void nonConfirmedReturnsClientError() {
        starter.position = Position.LEADER;
        var attempts = new AtomicInteger();
        var ret = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.toLeaderFunc(url -> {
            attempts.incrementAndGet();
            call("/bad-request", null);
        }, "test"));
        assertInstanceOf(NodeHttpClient.CallException.class, ret);
        assertEquals(1, attempts.get());
    }

    @Test
    void confirmedRetriesTransientFailureUntilItSucceeds() {
        starter.position = Position.LEADER;
        starter.heartbeatSendingIntervalMillis = 50;
        var attempts = new AtomicInteger();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.toLeaderFuncConfirmed(url ->
                call(attempts.incrementAndGet() < 3 ? "/unavailable" : "/ok", null), "test"));
        assertEquals(3, attempts.get());
    }

    @Test
    void confirmedStopsWhenInterruptedWhileWaitingToRetry() throws Exception {
        starter.position = Position.LEADER;
        starter.heartbeatSendingIntervalMillis = 60_000;
        var firstAttempt = new CountDownLatch(1);
        var outcome = runInterruptible(() -> starter.toLeaderFuncConfirmed(url -> {
            firstAttempt.countDown();
            call("/unavailable", null);
        }, "test"), firstAttempt, 0);
        assertInstanceOf(CancellationException.class, outcome.thrown());
        assertTrue(outcome.interruptFlagKept());
    }

    @Test
    void confirmedStopsWhenInterruptedWhileNoLeaderIsFound() throws Exception {
        // no peers and this node's own server is not running: the leader is never found
        starter.position = Position.FOLLOWER;
        starter.heartbeatSendingIntervalMillis = 60_000;
        var outcome = runInterruptible(() -> starter.toLeaderFuncConfirmed(url -> {}, "test"), new CountDownLatch(0), 300);
        assertInstanceOf(CancellationException.class, outcome.thrown());
        assertTrue(outcome.interruptFlagKept());
    }

    @Test
    void nonConfirmedReturnsWhenAlreadyInterrupted() throws Exception {
        starter.position = Position.LEADER;
        var ret = new AtomicReference<Throwable>();
        var attempted = new AtomicBoolean();
        var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            ret.set(starter.toLeaderFunc(url -> attempted.set(true), "test"));
        });
        worker.start();
        worker.join(5000);
        assertInstanceOf(CancellationException.class, ret.get());
        assertFalse(attempted.get());
    }

    @Test
    void leaderLookupReturnsOnFirstMatchWithoutWaitingForHungPeer() {
        starter.position = Position.FOLLOWER;
        starter.nodeTargetUrls.addAll(List.of(stub.url("/hung"), stub.url("/follower"), stub.url("/leader")));
        var found = new AtomicReference<String>();
        long begin = System.nanoTime();
        var ret = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> starter.toLeaderFunc(found::set, "test"));
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        assertNull(ret);
        assertEquals(stub.url("/leader"), found.get());
        // waiting for the hung peer would take the whole 4 s probe timeout
        assertTrue(elapsedMillis < 2000, "took " + elapsedMillis + " ms");
    }

    @Test
    void indexLookupReturnsOnFirstMatchWithoutWaitingForHungPeer() {
        starter.nodeTargetUrls.addAll(List.of(stub.url("/hung"), stub.url("/follower"), stub.url("/leader")));
        var found = new AtomicReference<String>();
        long begin = System.nanoTime();
        var ret = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> starter.toIndexFunc(3, found::set, "test"));
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        assertNull(ret);
        assertEquals(stub.url("/follower"), found.get());
        assertTrue(elapsedMillis < 2000, "took " + elapsedMillis + " ms");
    }

    @Test
    void leaderLookupKeepsTheConnectionOfAPeerThatAnswersAfterTheMatch() throws Exception {
        starter.position = Position.FOLLOWER;
        starter.nodeTargetUrls.addAll(List.of(stub.url("/pooled-leader"), stub.url("/slow-follower")));
        int lookups = 20;
        for (int i = 0; i < lookups; i++) {
            assertNull(assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.toLeaderFunc(url -> {}, "test")));
            // lets the follower's later answer arrive, so that the next lookup finds its connection idle
            Thread.sleep(150);
        }
        // interrupting the follower's probe right after the match closed its connection: one new connection per lookup
        int followerConnections = stub.connections("/slow-follower");
        assertTrue(followerConnections <= 2, followerConnections + " connections for " + lookups + " lookups");
        assertTrue(stub.connections("/pooled-leader") <= 2, stub.connections("/pooled-leader") + " leader connections");
    }

    @Test
    void hungProbeIsInterruptedAfterTheGracePeriod() throws Exception {
        starter.position = Position.FOLLOWER;
        starter.nodeTargetUrls.addAll(List.of(stub.url("/hung"), stub.url("/leader")));
        assertNull(assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.toLeaderFunc(url -> {}, "test")));

        var redirectFunction = ClusterStarter.class.getDeclaredField("redirectFunction");
        redirectFunction.setAccessible(true);
        var executorField = ClusterRedirectFunction.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        var executor = (ThreadPoolExecutor) executorField.get(redirectFunction.get(starter));
        long begin = System.nanoTime();
        while (executor.getActiveCount() > 0 && System.nanoTime() - begin < 3_000_000_000L)
            Thread.sleep(20);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        // left alone, the hung probe would hold its thread until the 4 s probe timeout
        assertEquals(0, executor.getActiveCount(), "probe still running after " + elapsedMillis + " ms");
    }

    @Test
    void lookupWithoutMatchIsBoundedByProbeTimeout() {
        starter.nodeTargetUrls.addAll(List.of(stub.url("/hung"), stub.url("/leader")));
        long begin = System.nanoTime();
        var ret = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> starter.toIndexFunc(9, url -> fail("no node 9"), "test"));
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        assertNotNull(ret);
        // the hung probe ends at the 4 s probe timeout, not at the 60 s read timeout
        assertTrue(elapsedMillis >= 3000 && elapsedMillis < 15000, "took " + elapsedMillis + " ms");
    }

    private record Outcome(Throwable thrown, boolean interruptFlagKept) {}

    /** runs the task on its own thread, interrupts it once started (+ delay) and waits for it to stop */
    private static Outcome runInterruptible(Runnable task, CountDownLatch started, long delayMillis) throws Exception {
        var thrown = new AtomicReference<Throwable>();
        var interruptFlagKept = new AtomicBoolean();
        var worker = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                thrown.set(e);
                interruptFlagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.setDaemon(true);
        worker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(delayMillis);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive(), "still retrying after interrupt");
        return new Outcome(thrown.get(), interruptFlagKept.get());
    }
}
