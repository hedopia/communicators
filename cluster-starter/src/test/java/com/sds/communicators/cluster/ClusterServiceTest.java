package com.sds.communicators.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Shared-object updates and cluster activation on real nodes listening on loopback ports. */
class ClusterServiceTest {
    /** a peer's answer to a batch of changes: all applied */
    private static final String APPLIED = "{\"applied\":true,\"seq\":null}";
    private static final String CHANGES = "/cluster/internal/check-shared-object-changes/";
    private static StubServer stub;
    private final List<ClusterStarter> started = new ArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        stub = new StubServer().hang("/hung");
    }

    @AfterAll
    static void stopStub() {
        stub.close();
    }

    @AfterEach
    void disposeNodes() {
        started.forEach(ClusterStarter::dispose);
    }

    private ClusterStarter startNode(int quorum, int leaderLostTimeoutSeconds, ClusterEvents events) throws Throwable {
        int port = StubServer.freePort();
        var starter = ClusterStarter.builder(Set.of(url(port)), port, 1)
                .setQuorum(quorum)
                .setLeaderLostTimeoutSeconds(leaderLostTimeoutSeconds)
                .setHeartbeatSendingIntervalMillis(200)
                .setClusterEvents(events)
                .build();
        started.add(starter);
        starter.start();
        return starter;
    }

    /** a single node that is LEADER right away */
    private ClusterStarter startLeader() throws Throwable {
        var starter = startNode(0, 0, null);
        assertEquals(Position.LEADER, starter.getPosition());
        return starter;
    }

    private static String url(int port) {
        return "http://127.0.0.1:" + port;
    }

    private static Map<Integer, Long> seq(ClusterStarter starter) {
        return starter.getNodeHttpClient().call(starter.nodeUrl + "/cluster/shared-object-seq", "GET", null,
                TypeFactory.defaultInstance().constructMapType(Map.class, Integer.class, Long.class));
    }

    private static String internal(ClusterStarter starter, String path) {
        return starter.nodeUrl + "/cluster" + ClusterInternalClient.INTERNAL_PATH + path;
    }

    private static ClusterService service(ClusterStarter starter) throws Exception {
        var field = ClusterStarter.class.getDeclaredField("clusterService");
        field.setAccessible(true);
        return (ClusterService) field.get(starter);
    }

    /** the sequences of the changes a peer got in batches, in the order it got them */
    private static List<Long> batchedSequences(StubServer peers, String peer) throws Exception {
        var json = new ObjectMapper();
        List<Long> sequences = new ArrayList<>();
        for (var request : peers.requests(peer + CHANGES)) {
            for (var change : json.readTree(request.substring(request.indexOf(' ') + 1)))
                sequences.add(change.get("seq").asLong());
        }
        return sequences;
    }

    /** the sequences of the changes a peer got one per call, in the order it got them */
    private static List<Long> perChangeSequences(StubServer peers, String peer) throws Exception {
        var json = new ObjectMapper();
        List<Long> sequences = new ArrayList<>();
        for (var request : peers.requests(peer + "/cluster/internal/check-merge-shared-object/"))
            sequences.add(json.readTree(request.substring(request.indexOf(' ') + 1)).get("seq").asLong());
        return sequences;
    }

    private static List<Long> sequences(long base, int from, int to) {
        List<Long> expected = new ArrayList<>();
        for (int i = from; i <= to; i++)
            expected.add(base + i);
        return expected;
    }

    private static String status(int nodeIndex, Position position) throws Exception {
        return new ObjectMapper().writeValueAsString(new NodeStatus(nodeIndex, position, true));
    }

    private static ClusterService.SharedObject replicaOf(int nodeIndex, long seq, int value) {
        return new ClusterService.SharedObject(Map.of(nodeIndex, new HashMap<>(Map.of("v", value))), Map.of(nodeIndex, seq));
    }

    private static long timedMerge(ClusterStarter starter, Object value, String... path) {
        long begin = System.nanoTime();
        starter.mergeSharedObject(value, path);
        return (System.nanoTime() - begin) / 1_000_000;
    }

    @Test
    void mergeOnLeaderAppliesLocally() throws Throwable {
        var starter = startLeader();
        long base = seq(starter).get(1);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.mergeSharedObject(Map.of("a", 1)));
        assertEquals(Map.of("a", 1), starter.getSharedObject());
        assertEquals(base + 1, seq(starter).get(1));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.mergeSharedObject(2, "b", "c"));
        assertEquals(Map.of("a", 1, "b", Map.of("c", 2)), starter.getSharedObject());
        assertEquals(base + 2, seq(starter).get(1));
    }

    @Test
    void unserializableValueIsRejectedWithoutAnyChange() throws Throwable {
        var starter = startLeader();
        long base = seq(starter).get(1);
        starter.mergeSharedObject(Map.of("a", 1));

        Map<String, Object> selfReferencing = new HashMap<>();
        selfReferencing.put("self", selfReferencing);
        List<Runnable> merges = List.of(
                () -> starter.mergeSharedObject(Map.of("bad", new Object())),
                () -> starter.mergeSharedObject(new Object(), "x", "y"),
                () -> starter.mergeSharedObject(selfReferencing));
        for (var merge : merges) {
            var e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IllegalArgumentException.class, merge::run));
            assertTrue(e.getMessage().startsWith("shared-object value is not JSON serializable"), e.getMessage());
            assertEquals(Map.of("a", 1), starter.getSharedObject());
            assertEquals(base + 1, seq(starter).get(1));
        }
    }

    @Test
    void mergedValueIsDetachedFromTheCaller() throws Throwable {
        var starter = startLeader();
        var list = new ArrayList<>(List.of(1));
        var nested = new HashMap<String, Object>(Map.of("n", 1));
        starter.mergeSharedObject(Map.of("list", list, "nested", nested));
        list.add(2);
        nested.put("m", 2);
        assertEquals(List.of(1), starter.getItem(1, new String[]{"list"}));
        assertEquals(Map.of("n", 1), starter.getItem(1, new String[]{"nested"}));
    }

    @Test
    void deleteWithEmptyPathIsNoOp() throws Throwable {
        var starter = startLeader();
        long base = seq(starter).get(1);
        starter.mergeSharedObject(Map.of("a", 1, "b", 2));
        assertDoesNotThrow(() -> starter.deleteSharedObject());
        assertDoesNotThrow(() -> starter.deleteSharedObject(List.of(List.of())));
        assertEquals(base + 1, seq(starter).get(1));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.deleteSharedObject("a"));
        assertEquals(Map.of("b", 2), starter.getSharedObject());
        assertEquals(base + 2, seq(starter).get(1));
    }

    @Test
    void leaderUpdatesDoNotWaitForHungPeer() throws Throwable {
        var starter = startLeader();
        long base = seq(starter).get(1);
        // added after start: start() itself probes every peer with the full read timeout
        starter.nodeTargetUrls.add(stub.url("/hung"));
        // let heartbeats to the hung peer get in flight
        Thread.sleep(500);

        for (int i = 1; i <= 2; i++) {
            int value = i;
            long begin = System.nanoTime();
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> starter.mergeSharedObject(value, "k" + value));
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            // before, the heartbeat and the check-merge fan-out each held syncMutex for the 60 s read timeout; now the
            // leader waits for the propagation of its own change, but not for a peer that never answered a heartbeat
            assertTrue(elapsedMillis < 1000, "merge " + i + " took " + elapsedMillis + " ms");
        }
        assertEquals(Map.of("k1", 1, "k2", 2), starter.getSharedObject());
        assertEquals(base + 2, seq(starter).get(1));
    }

    @Test
    void leaderWaitsForItsOwnChangeToReachFollowersAtMostTheProbeTimeout() throws Throwable {
        try (var peers = new StubServer()
                .respond("/peer/cluster/internal/check-shared-object-changes", 200, APPLIED)
                .respond("/peer", 200, "true")
                .respond("/slow/cluster/internal/heartbeat", 200, "")
                .respond("/slow", 3000, 200, APPLIED)) {
            int port = StubServer.freePort();
            // probe timeout: min(max(2 x 200, 1000), 60000) = 1000 ms
            var starter = ClusterStarter.builder(Set.of(url(port)), port, 1)
                    .setLeaderLostTimeoutSeconds(0)
                    .setHeartbeatSendingIntervalMillis(200)
                    .build();
            started.add(starter);
            starter.start();
            starter.nodeTargetUrls.add(peers.url("/peer"));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/peer/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);

            starter.mergeSharedObject(1, "k");
            // acknowledged only after the reachable follower received it
            assertTrue(peers.requests("/peer" + CHANGES).stream().anyMatch(request -> request.contains("\"k\":1")),
                    "leader change acknowledged before it was propagated");

            starter.nodeTargetUrls.add(peers.url("/slow"));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/slow/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);
            long begin = System.nanoTime();
            starter.mergeSharedObject(2, "k");
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            assertTrue(elapsedMillis >= 800 && elapsedMillis < 2500, "merge behind a slow follower took " + elapsedMillis + " ms");
        }
    }

    @Test
    void concurrentLeaderChangesDoNotWaitForEachOthersPropagation() throws Throwable {
        // each propagation succeeds, but takes most of the probe timeout
        try (var peers = new StubServer()
                .respond("/slow/cluster/internal/heartbeat", 200, "")
                .respond("/slow/cluster/internal/check-shared-object-changes", 700, 200, APPLIED)
                .respond("/slow", 700, 200, "true")) {
            int port = StubServer.freePort();
            // probe timeout: min(max(2 x 200, 1000), 60000) = 1000 ms
            var starter = ClusterStarter.builder(Set.of(url(port)), port, 1)
                    .setLeaderLostTimeoutSeconds(0)
                    .setHeartbeatSendingIntervalMillis(200)
                    .build();
            started.add(starter);
            starter.start();
            starter.nodeTargetUrls.add(peers.url("/slow"));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/slow/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);

            int writers = 4;
            var go = new CountDownLatch(1);
            var elapsed = Collections.synchronizedList(new ArrayList<Long>());
            var threads = new ArrayList<Thread>();
            for (int i = 1; i <= writers; i++) {
                int value = i;
                var thread = new Thread(() -> {
                    try {
                        go.await();
                        long begin = System.nanoTime();
                        starter.mergeSharedObject(value, "k" + value);
                        elapsed.add((System.nanoTime() - begin) / 1_000_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                thread.start();
                threads.add(thread);
            }
            go.countDown();
            for (var thread : threads)
                thread.join(15000);
            assertEquals(writers, elapsed.size(), "writers not finished: " + elapsed);
            // before, each write waited inside the write lock for its own propagation, so the last one took 4 x 700 ms
            for (long millis : elapsed)
                assertTrue(millis < 2000, "leader writes behind a slow follower took " + elapsed + " ms");
            assertEquals(4, starter.getSharedObject().size());
            // the peer answers within the budget, so it is waited for, and it got every change
            assertEquals(4, batchedSequences(peers, "/slow").size());
        }
    }

    @Test
    void changesQueuedBehindSlowPeerReachOtherPeersInBatchesInOrder() throws Throwable {
        // answers heartbeats, so changes are queued for it, but never answers a batch
        try (var peers = new StubServer()
                .respond("/slow/cluster/internal/heartbeat", 200, "")
                .hang("/slow")
                .respond("/peer/cluster/internal/check-shared-object-changes", 200, APPLIED)
                .respond("/peer", 200, "true")) {
            int port = StubServer.freePort();
            // probe timeout: min(max(2 x 200, 1000), 1500) = 1000 ms
            var starter = ClusterStarter.builder(Set.of(url(port)), port, 1)
                    .setLeaderLostTimeoutSeconds(0)
                    .setHeartbeatSendingIntervalMillis(200)
                    .setReadTimeoutMillis(1500)
                    .build();
            started.add(starter);
            starter.start();
            starter.nodeTargetUrls.addAll(List.of(peers.url("/slow"), peers.url("/peer")));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/slow/cluster/internal/heartbeat").isEmpty()
                    && !peers.requests("/peer/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);

            // changes of this node queued directly, the way a sender's changes reach the leader (without waiting)
            var service = service(starter);
            long base = seq(starter).get(1);
            int changes = 200;
            long begin = System.nanoTime();
            for (int i = 1; i <= changes; i++)
                assertNull(service.setSharedObjectToLeader(1, new ClusterService.MergeSharedObjectInfo(base + i, Map.of("k", i))));

            // each peer has its own lane: before, every batch went to the peers together and the next one waited for the
            // hung peer (the probe timeout, 1 s); before that, every change took one call, and waited for it
            long newest = base + changes;
            TestCluster.await(Duration.ofSeconds(10), () -> {
                try {
                    return batchedSequences(peers, "/peer").contains(newest);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, () -> "newest change not propagated");
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            assertTrue(elapsedMillis < 900, "changes reached the other peer after " + elapsedMillis + " ms");
            // every change, in sequence order: not only the newest of each batch, which a follower still behind refuses
            // and then gets the whole object instead
            assertEquals(sequences(base, 1, changes), batchedSequences(peers, "/peer"));
            int batches = peers.requests("/peer" + CHANGES).size();
            assertTrue(batches < changes, "one call per change: " + batches);
            assertEquals(List.of(), peers.requests("/peer/cluster/internal/overwrite-shared-object/"));
            assertEquals(List.of(), peers.requests("/peer/cluster/internal/check-merge-shared-object/"));
        }
    }

    @Test
    void backlogBeyondTheBoundIsSentAsOneWholeObject() throws Throwable {
        // each batch is answered after 500 ms: the peer's lane is busy meanwhile
        try (var peers = new StubServer()
                .respond("/peer/cluster/internal/check-shared-object-changes", 500, 200, APPLIED)
                .respond("/peer", 200, "true")) {
            var starter = startLeader();
            starter.nodeTargetUrls.add(peers.url("/peer"));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/peer/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);

            var service = service(starter);
            long base = seq(starter).get(1);
            assertNull(service.setSharedObjectToLeader(1, new ClusterService.MergeSharedObjectInfo(base + 1, Map.of("k", 1))));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/peer" + CHANGES).isEmpty(), () -> "first batch not sent");
            // while it is on its way, more changes queue than a lane keeps one by one
            for (int i = 2; i <= 300; i++)
                assertNull(service.setSharedObjectToLeader(1, new ClusterService.MergeSharedObjectInfo(base + i, Map.of("k", i))));

            TestCluster.await(Duration.ofSeconds(10), () -> !peers.requests("/peer/cluster/internal/overwrite-shared-object/").isEmpty(),
                    () -> "whole object not sent");
            Thread.sleep(800);
            assertEquals(1, peers.requests("/peer/cluster/internal/overwrite-shared-object/").size());
            assertEquals(List.of(base + 1), batchedSequences(peers, "/peer"));
            assertEquals(List.of(), peers.requests("/peer/cluster/internal/check-merge-shared-object/"));
        }
    }

    @Test
    void batchesFallBackToOneChangePerCallForAPeerOfAnOlderVersion() throws Throwable {
        // an older node: no batch route (404), the per-change routes only
        try (var peers = new StubServer()
                .respond("/old/cluster/internal/check-shared-object-changes", 404, "")
                .respond("/old", 200, "true")) {
            var starter = startLeader();
            starter.nodeTargetUrls.add(peers.url("/old"));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/old/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);

            var service = service(starter);
            long base = seq(starter).get(1);
            for (int round = 0; round < 2; round++) {
                int from = round * 30 + 1;
                int to = from + 29;
                for (int i = from; i <= to; i++)
                    assertNull(service.setSharedObjectToLeader(1, new ClusterService.MergeSharedObjectInfo(base + i, Map.of("k", i))));
                long newest = base + to;
                TestCluster.await(Duration.ofSeconds(10), () -> {
                    try {
                        return perChangeSequences(peers, "/old").contains(newest);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, () -> "newest change not propagated");
                // every change, one per call and in sequence order, as an older leader sends them
                assertEquals(sequences(base, 1, to), perChangeSequences(peers, "/old"));
                // the batch route was asked once: the answer is remembered
                assertEquals(1, peers.requests("/old" + CHANGES).size());
            }
            assertEquals(List.of(), peers.requests("/old/cluster/internal/overwrite-shared-object/"));
        }
    }

    @Test
    void leaderWithoutReachablePeersNeitherHandsItsChangesOffNorWaits() throws Throwable {
        var starter = startLeader();
        // nothing listens there: it never answers a heartbeat, so it is not reachable
        starter.nodeTargetUrls.add(url(StubServer.freePort()));
        Thread.sleep(500);
        var service = service(starter);
        long base = seq(starter).get(1);
        int writes = 20000;
        long begin = System.nanoTime();
        for (int i = 0; i < writes; i++)
            starter.mergeSharedObject(i, "k" + (i % 100));
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        assertEquals(base + writes, seq(starter).get(1));
        // no lane: every change was marked done where it was made, without a hand-off to a propagation thread
        var field = ClusterService.class.getDeclaredField("propagationLanes");
        field.setAccessible(true);
        synchronized (field.get(service)) {
            assertEquals(Map.of(), field.get(service));
        }
        // a generous bound (tens of thousands of writes per second are usual)
        assertTrue(elapsedMillis < 10000, writes + " writes took " + elapsedMillis + " ms");
    }

    @Test
    void laggingPeerIsNotWaitedForUntilABatchIsAnsweredWithinTheBudget() throws Throwable {
        var slowDelay = new AtomicLong(1500);
        try (var peers = new StubServer()
                .respond("/fast/cluster/internal/check-shared-object-changes", 200, APPLIED)
                .respond("/fast", 200, "true")
                .respond("/slow/cluster/internal/heartbeat", 200, "")
                .respond("/slow/cluster/internal/check-shared-object-changes", slowDelay::get, 200, APPLIED)
                .respond("/slow", 200, "true")) {
            int port = StubServer.freePort();
            // probe timeout, which is also the budget of a batch: min(max(2 x 200, 1000), 60000) = 1000 ms
            var starter = ClusterStarter.builder(Set.of(url(port)), port, 1)
                    .setLeaderLostTimeoutSeconds(0)
                    .setHeartbeatSendingIntervalMillis(200)
                    .build();
            started.add(starter);
            starter.start();
            starter.nodeTargetUrls.addAll(List.of(peers.url("/fast"), peers.url("/slow")));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/fast/cluster/internal/heartbeat").isEmpty()
                    && !peers.requests("/slow/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat");
            Thread.sleep(300);

            // the first change waits for the slow peer until its batch runs over the budget
            long first = timedMerge(starter, 0, "k");
            assertTrue(first >= 800 && first < 2500, "first change took " + first + " ms");

            // from then on, it lags: not waited for, while it still gets the changes
            int slowBatches = peers.requests("/slow" + CHANGES).size();
            int n = 0;
            long max = 0;
            long until = System.nanoTime() + Duration.ofMillis(2500).toNanos();
            while (System.nanoTime() < until) {
                max = Math.max(max, timedMerge(starter, ++n, "k"));
                Thread.sleep(20);
            }
            // before, every change waited for it up to the probe timeout
            assertTrue(max < 500, "leader waited " + max + " ms for a lagging peer");
            assertTrue(peers.requests("/slow" + CHANGES).size() > slowBatches, "lagging peer no longer gets batches");
            assertTrue(batchedSequences(peers, "/fast").size() > n, "fast peer did not get every change");

            // a batch answered within the budget again: waited for again
            slowDelay.set(300);
            boolean waited = false;
            until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!waited && System.nanoTime() < until) {
                waited = timedMerge(starter, ++n, "k") >= 250;
                Thread.sleep(20);
            }
            assertTrue(waited, "a peer answering within the budget again is not waited for");
        }
    }

    @Test
    void splitBrainWinnerAnswersRightAwayAndResolvesOncePerHandover() throws Throwable {
        var resolved = new CopyOnWriteArrayList<Long>();
        var overwritten = new CopyOnWriteArrayList<Integer>();
        var starter = startNode(0, 0, new ClusterEvents()
                .splitBrainResolved("record", () -> resolved.add(System.nanoTime()))
                .overwritten("record", overwritten::add));
        // a peer that accepts the connection and never answers: the winner checks every peer after a heal
        starter.nodeTargetUrls.add(stub.url("/hung"));
        var client = starter.getNodeHttpClient();
        var handover = new ClusterService.SharedObject(Map.of(7, new HashMap<>(Map.of("v", 1))), Map.of(7, 5L));

        long elapsedMillis = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            long begin = System.nanoTime();
            client.call(internal(starter, "/sync-shared-object/3?handover=a"), "POST", handover, null);
            return (System.nanoTime() - begin) / 1_000_000;
        });
        // before, it answered only after every peer was checked, each with the read timeout (60 s): the demoted node's
        // call failed, and it sent everything again with each heartbeat
        assertTrue(elapsedMillis < 1000, "answered after " + elapsedMillis + " ms");
        TestCluster.await(Duration.ofSeconds(5), () -> overwritten.equals(List.of(7)), () -> "node 7 not taken over: " + overwritten);
        // resolved once the hung peer's check has timed out (probe timeout, 1 s)
        TestCluster.await(Duration.ofSeconds(5), () -> resolved.size() == 1, () -> "split brain not resolved");

        // the same handover again (its answer got lost): not resolved again
        client.call(internal(starter, "/sync-shared-object/3?handover=a"), "POST", handover, null);
        Thread.sleep(2000);
        assertEquals(1, resolved.size());
        // another one, from the next heal, is
        client.call(internal(starter, "/sync-shared-object/3?handover=b"), "POST", handover, null);
        TestCluster.await(Duration.ofSeconds(5), () -> resolved.size() == 2, () -> "next split brain not resolved");
    }

    @Test
    void handoverIsDeliveredOnceTheLeadersHeartbeatListsItsObjects() throws Throwable {
        // lost after 1 s; the leader heartbeats below come from node 9, which cannot be reached, so no handover call succeeds
        var starter = startNode(1, 1, null);
        var service = service(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
        service.overwriteSharedObject(6, new ClusterService.MergeSharedObjectInfo(20, new HashMap<>(Map.of("v", 20))));

        // a leader that took over earlier: this node steps down, and hands over what it holds
        service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
        assertEquals(Position.FOLLOWER, starter.getPosition());
        // the leader lacks them: kept
        service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
        assertEquals(Set.of(1, 5, 6), seq(starter).keySet());
        // it holds node 5's, but not node 6's yet: node 6's is kept
        service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L, 5, 10L));
        assertEquals(Set.of(1, 5, 6), seq(starter).keySet());
        // node 6's failover finished on the leader (with that object): dropped, and known to be removed
        service.replicaDeleted(6, 20L, false);
        assertEquals(Set.of(1, 5), seq(starter).keySet());
        // now its heartbeat covers everything handed over: delivered, although no call succeeded
        service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L, 5, 10L));
        // from then on, its lack of a replica decides again
        service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
        assertEquals(Set.of(1), seq(starter).keySet());
    }

    @Test
    void demotedNodeHandsOverOneCallAtATimeWithBackoffUntilDelivered() throws Throwable {
        var syncStatus = new java.util.concurrent.atomic.AtomicInteger(503);
        // node 9, the leader that took over earlier: its sync route answers after 300 ms, first with 503
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, new ObjectMapper().writeValueAsString(new NodeStatus(9, Position.LEADER, true)))
                .respond("/winner/cluster/internal/sync-shared-object/", () -> 300, syncStatus::get, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String sync = "/winner/cluster/internal/sync-shared-object/";

            long begin = System.nanoTime();
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            assertEquals(Position.FOLLOWER, starter.getPosition());
            // the handover is sent from another thread: before, the heartbeat waited for it
            assertTrue(elapsedMillis < 250, "demoting heartbeat handled in " + elapsedMillis + " ms");

            // a leader heartbeat every 100 ms for 3 s: before, one call per heartbeat (and two at once right after the
            // demotion); now one at a time, and after each failure only after 1, 2, 4, 8, 16 heartbeat intervals
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                Thread.sleep(100);
            }
            int failed = winner.requests(sync).size();
            assertTrue(failed >= 2 && failed <= 5, failed + " handover calls in 3 s");
            // kept while not delivered
            assertEquals(Set.of(1, 5), seq(starter).keySet());

            syncStatus.set(200);
            TestCluster.await(Duration.ofSeconds(10), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                // delivered: no longer kept, as the leader lacks it
                return !seq(starter).containsKey(5);
            }, () -> "handover not delivered: " + winner.requests(sync).size() + " calls");
            int calls = winner.requests(sync).size();
            for (int i = 0; i < 10; i++) {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                Thread.sleep(100);
            }
            assertEquals(calls, winner.requests(sync).size(), "handover sent again after it was delivered");
        }
    }

    @Test
    void demotedNodeHandsOverOnceEvenWhenTheDemotingHeartbeatListsEverything() throws Throwable {
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", 100, 200, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String sync = "/winner/cluster/internal/sync-shared-object/";

            // the winner took over node 5's object already (it pulled it from a node it hears again): its heartbeat
            // lists everything this node holds. Before, that cleared the handover right there, before it was sent,
            // and the winner never resolved the split brain
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L, 5, 10L));
                Thread.sleep(100);
            }
            assertEquals(Position.FOLLOWER, starter.getPosition());
            assertEquals(1, winner.requests(sync).size(), "handover calls");
        }
    }

    @Test
    void winnerIsToldOnceWhenItsHeartbeatListsEverythingBeforeAHandoverCallSucceeded() throws Throwable {
        var syncStatus = new java.util.concurrent.atomic.AtomicInteger(503);
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", () -> 0, syncStatus::get, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String sync = "/winner/cluster/internal/sync-shared-object/";

            // demoted: the first call fails
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            TestCluster.await(Duration.ofSeconds(5), () -> !winner.requests(sync).isEmpty(), () -> "no handover call");
            syncStatus.set(200);
            // then the winner's heartbeat lists node 5 (it got it elsewhere): the objects are not sent again, but the
            // winner is told once, which resolves the split brain there
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L, 5, 10L));
                return winner.requests(sync).size() >= 2;
            }, () -> "winner not told");
            long until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L, 5, 10L));
                Thread.sleep(100);
            }
            var calls = winner.requests(sync);
            assertEquals(2, calls.size(), "handover calls: " + calls);
            assertTrue(calls.get(0).contains("\"5\":{\"v\":10}"), calls.get(0));
            assertTrue(calls.get(1).contains("\"sharedObject\":{}") && calls.get(1).contains("\"sharedObjectSeq\":{}"), calls.get(1));
        }
    }

    @Test
    void handoverIsDueAgainAsSoonAsThisNodesHeartbeatReachesTheLeaderAgain() throws Throwable {
        var reachable = new java.util.concurrent.atomic.AtomicBoolean(false);
        // node 9 hears this node's heartbeats only once the one-way link heals; it is found through its status meanwhile
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/heartbeat", () -> 0, () -> reachable.get() ? 200 : 503, "9")
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", () -> 0, () -> reachable.get() ? 200 : 503, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String sync = "/winner/cluster/internal/sync-shared-object/";

            // demoted by node 9 during a one-way half-heal: every handover call fails, the next one after 1, 2, 4, 8 and
            // then 16 heartbeat intervals
            TestCluster.await(Duration.ofSeconds(10), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return winner.requests(sync).size() >= 5;
            }, () -> "handover calls: " + winner.requests(sync).size());
            assertEquals(Position.FOLLOWER, starter.getPosition());
            // the link heals right after the fifth failure, whose backoff is 16 intervals (3.2 s): before, the handover
            // waited for it, and the winner's sync and splitBrainResolved with it
            reachable.set(true);
            long begin = System.nanoTime();
            TestCluster.await(Duration.ofSeconds(10), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return winner.requests(sync).size() >= 6;
            }, () -> "handover not sent again");
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            assertTrue(elapsedMillis < 1500, "handover sent " + elapsedMillis + " ms after this node's heartbeats reached the winner again");
        }
    }

    @Test
    void handoverIsDueAgainOnceTheLeaderIsReachedAfterItsUrlWasOutOfReach() throws Throwable {
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/heartbeat", 200, "9")
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", 200, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            String url = winner.url("/winner");
            starter.nodeTargetUrls.add(url);
            TestCluster.await(Duration.ofSeconds(5), () -> winner.requests("/winner/cluster/internal/heartbeat").size() >= 2, () -> "no heartbeat");
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String sync = "/winner/cluster/internal/sync-shared-object/";

            // a partition: node 9's url is out of this node's reach, so no heartbeat of this node fails there (none is
            // sent), and no status lookup finds node 9. Its heartbeats reach this node (a one-way half-heal) and demote
            // it: each handover call finds no url, the next one after 1, 2, 4, 8 and then 16 heartbeat intervals
            starter.nodeTargetUrls.remove(url);
            long until = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                Thread.sleep(100);
            }
            assertEquals(Position.FOLLOWER, starter.getPosition());
            assertEquals(List.of(), winner.requests(sync));
            // the full heal, about a second into the 16 intervals (3.2 s) after the fifth failure: this node's heartbeat
            // reaches node 9 again, after a gap, and the handover goes out right away
            starter.nodeTargetUrls.add(url);
            long begin = System.nanoTime();
            TestCluster.await(Duration.ofSeconds(10), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return !winner.requests(sync).isEmpty();
            }, () -> "handover not sent");
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            assertTrue(elapsedMillis < 1500, "handover sent " + elapsedMillis + " ms after node 9 was in reach again");
        }
    }

    @Test
    void concurrentHeartbeatsOfTheWinnerMakeOneSplitBrainHandoverThatNoOfferReplaces() throws Throwable {
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/heartbeat", 200, "9")
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", 200, "")
                .respond("/winner/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            String sync = "/winner/cluster/internal/sync-shared-object/";
            for (int round = 1; round <= 20; round++) {
                service.forceToLeader();
                assertEquals(Position.LEADER, starter.getPosition());
                // node 5 died where only this node heard it: a follower of node 9 would offer it
                service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10 + round, new HashMap<>(Map.of("v", round))));
                int before = winner.requests(sync).size();

                // the heal: node 9's heartbeats are handled on several threads at once. Before, one that found no handover
                // yet stored an offer in place of the split-brain handover, whose call was then dropped, and two demoting
                // ones made two handovers: the winner resolved the split brain zero or two times
                var start = new CountDownLatch(1);
                var threads = new ArrayList<Thread>();
                for (int i = 0; i < 4; i++) {
                    var thread = new Thread(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                    });
                    thread.start();
                    threads.add(thread);
                }
                start.countDown();
                for (var thread : threads)
                    thread.join();
                assertEquals(Position.FOLLOWER, starter.getPosition());
                for (int i = 0; i < 5; i++) {
                    service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                    Thread.sleep(100);
                }
                var calls = winner.requests(sync);
                assertEquals(1, calls.size() - before, "round " + round + ", split-brain handover calls: " + calls.subList(before, calls.size()));
                assertTrue(calls.get(before).contains("\"5\":{\"v\":" + round + "}"), calls.get(before));
            }
        }
    }

    @Test
    void handoverAnsweredByAWinnerThatDiedIsOfferedToTheNextLeader() throws Throwable {
        try (var peers = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner", 200, "")
                .respond("/next/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/next/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/next", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(peers.url("/winner"), peers.url("/next")));
            // node 5 died where only this node heard it
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));

            // node 9 demotes this node, and answers its handover
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/winner/cluster/internal/sync-shared-object/").isEmpty(),
                    () -> "no handover call");
            Thread.sleep(300);
            // node 9 dies before its followers got node 5's object, and node 8 leads now, without it. Before, node 8's
            // first heartbeat cleared the delivered handover, and its second one dropped node 5's copy: never failed over
            String offer = "/next/cluster/internal/offer-shared-object/";
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                return !peers.requests(offer).isEmpty();
            }, () -> "node 5's object not offered to the next leader: " + seq(starter));
            assertTrue(peers.requests(offer).get(0).contains("\"5\":{\"v\":10}"), peers.requests(offer).get(0));
        }
    }

    @Test
    void handoverAnsweredByAWinnerThatDiedIsOfferedSoonToTheLeaderElectedAfterIt() throws Throwable {
        try (var peers = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", 200, "")
                .respond("/winner", 200, "")
                .respond("/next/cluster/internal/heartbeat", 200, "8")
                .respond("/next/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/next/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/next", 200, "")) {
            // lost after 2 s: a leader counts as heard lately for 2 s after its last heartbeat
            var starter = startNode(1, 2, null);
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(peers.url("/winner"), peers.url("/next")));
            // node 5 died where only this node heard it
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));

            // node 9 demotes this node, answers its handover, and dies right after
            long lastHeard = System.currentTimeMillis();
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/winner/cluster/internal/sync-shared-object/").isEmpty(),
                    () -> "no handover call");
            // node 8, which lacks node 5's object, is elected 300 ms later: a lookup that found no leader started the
            // election, well before the leader-lost timeout
            Thread.sleep(Math.max(0, lastHeard + 300 - System.currentTimeMillis()));
            long transitionTime = System.currentTimeMillis();
            String offer = "/next/cluster/internal/offer-shared-object/";
            long begin = System.nanoTime();
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, transitionTime, Map.of(8, 1L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return !peers.requests(offer).isEmpty();
            }, () -> "node 5's object not offered to node 8: " + seq(starter));
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            // before, the handover was kept for node 9 until it had not been heard for the leader-lost timeout: the offer,
            // and node 5's failover with it, came 1.7 s after node 8's first heartbeat. Now once node 9 has missed two
            // heartbeats (400 ms), node 8 having been elected after the last one
            assertTrue(elapsedMillis < 1000, "offered " + elapsedMillis + " ms after node 8's first heartbeat");
            assertTrue(peers.requests(offer).get(0).contains("\"5\":{\"v\":10}"), peers.requests(offer).get(0));
        }
    }

    @Test
    void onlyADropAgainstTheLeaderThatListedTheCopyCountsAsItsRemoval() throws Throwable {
        var json = new ObjectMapper();
        var deleted = new CopyOnWriteArrayList<String>();
        // node 8 leads and holds node 6's object; node 9, a split-brain leader heard for a moment, holds node 5's
        try (var leaders = new StubServer()
                .respond("/eight/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/eight/cluster/internal/shared-object/6", 200, json.writeValueAsString(new ClusterService.MergeSharedObjectInfo(20, Map.of("v", 20))))
                .respond("/eight", 200, "")
                .respond("/nine/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/nine/cluster/internal/shared-object/5", 200, json.writeValueAsString(new ClusterService.MergeSharedObjectInfo(10, Map.of("v", 10))))
                .respond("/nine", 200, "")) {
            var starter = startNode(1, 1, new ClusterEvents()
                    .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex + ":" + TestCluster.json(object))));
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(leaders.url("/eight"), leaders.url("/nine")));
            service.forceToFollower();

            // node 6's object comes from node 8, which lists it, and then no longer does: it removed it
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L, 6, 20L));
                return service.copyReplica(6, true) != null;
            }, () -> "node 6 not fetched");
            service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L, 6, 20L));
            service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
            assertNull(service.copyReplica(6, true));
            // node 5's object comes from node 9, and node 8 never held it: dropped, but node 8 did not remove it
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(9, Position.LEADER, 6, Map.of(9, 1L, 5, 10L));
                return service.copyReplica(5, true) != null;
            }, () -> "node 5 not fetched");
            service.heartbeatReceived(9, Position.LEADER, 6, Map.of(9, 1L, 5, 10L));
            service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
            assertNull(service.copyReplica(5, true));

            // this node leads later, and a follower offers both (their nodes are gone): before, both drops counted as
            // removals, so node 5's object was declined and never failed over
            service.forceToLeader();
            starter.getNodeHttpClient().call(internal(starter, "/offer-shared-object/2"), "POST", new ClusterService.SharedObject(
                    Map.of(5, new HashMap<>(Map.of("v", 10)), 6, new HashMap<>(Map.of("v", 20))), Map.of(5, 10L, 6, 20L)), null);
            TestCluster.await(Duration.ofSeconds(5), () -> deleted.contains("5:{\"v\":10}"), () -> "node 5 not failed over: " + deleted);
            Thread.sleep(1000);
            assertEquals(List.of("5:{\"v\":10}"), deleted);
        }
    }

    @Test
    void copiesTheLeaderDeclinesForItsMembersAreKeptAndOfferedAgain() throws Throwable {
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        // node 8 leads: node 5 is still its member at the first offer, and gone at the next one
        try (var leader = new StubServer()
                .respond("/leader/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/leader/cluster/internal/offer-shared-object/", () -> 0, () -> 200, () -> answers.getAndIncrement() == 0 ? "[5]" : "[]")
                .respond("/leader", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            service.forceToFollower();
            starter.nodeTargetUrls.add(leader.url("/leader"));
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String offer = "/leader/cluster/internal/offer-shared-object/";

            // a new leader lacks node 5's object, which this node holds and does not hear
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                // kept while declined: before, the answer counted as delivered and the copy was dropped
                assertTrue(seq(starter).containsKey(5), "node 5's copy dropped after " + leader.requests(offer).size() + " offer(s)");
                return leader.requests(offer).size() >= 2;
            }, () -> "declined copy not offered again");
            // taken over the second time: delivered, and from then on the leader's lack of it decides
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                return !seq(starter).containsKey(5);
            }, () -> "node 5's copy still held");
            assertEquals(2, leader.requests(offer).size());
            for (var request : leader.requests(offer))
                assertTrue(request.contains("\"5\":{\"v\":10}"), request);
        }
    }

    @Test
    void leaderAnswersAnOfferWithTheMembersItDeclined() throws Throwable {
        var starter = startNode(1, 1, null);
        var service = service(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        // node 8 is a member (its own object comes from itself), node 6 is not
        service.heartbeatReceived(8, Position.FOLLOWER, 0, null);
        var offered = new ClusterService.SharedObject(Map.of(8, new HashMap<>(Map.of("v", 8)), 6, new HashMap<>(Map.of("v", 6))), Map.of(8, 3L, 6, 4L));
        Set<Integer> declined = starter.getNodeHttpClient().call(internal(starter, "/offer-shared-object/2"), "POST", offered,
                TypeFactory.defaultInstance().constructCollectionType(Set.class, Integer.class));
        assertEquals(Set.of(8), declined);
        assertNull(service.copyReplica(8, true));
        assertEquals(Map.of("v", 6), service.copyReplica(6, true).obj);
    }

    @Test
    void offerReachesARestartedLeaderAtTheUrlItAnswersHeartbeatsAt() throws Throwable {
        // node 8 restarted and leads, but prepares still: it refuses status probes, and answers heartbeats with its index
        try (var leader = new StubServer()
                .respond("/leader/cluster/internal/heartbeat", 200, "8")
                .respond("/leader/cluster/internal/node-status", 400, "application is not prepared, get status ignored")
                .respond("/leader/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/leader", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(leader.url("/leader"));
            TestCluster.await(Duration.ofSeconds(5), () -> leader.requests("/leader/cluster/internal/heartbeat").size() >= 2, () -> "no heartbeat");
            service.forceToFollower();
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String offer = "/leader/cluster/internal/offer-shared-object/";

            long begin = System.nanoTime();
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                return !leader.requests(offer).isEmpty();
            }, () -> "no offer");
            long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
            // before, the leader was looked up by asking every node for its status, and each offer failed until it had prepared
            assertTrue(elapsedMillis < 1000, "offered after " + elapsedMillis + " ms");
        }
    }

    @Test
    void leadersHeardAlternatelyAreNotOfferedTheSameCopiesAgainAndAgain() throws Throwable {
        // two leaders of a split brain: node 8, which this node cannot send to, and node 9, which holds node 5's object
        try (var leaders = new StubServer()
                .respond("/eight/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/eight/cluster/internal/offer-shared-object/", 503, "unavailable")
                .respond("/eight", 200, "")
                .respond("/nine/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/nine", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(leaders.url("/eight"), leaders.url("/nine")));
            service.forceToFollower();
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));

            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                Thread.sleep(50);
                service.heartbeatReceived(9, Position.LEADER, 6, Map.of(9, 1L, 5, 10L));
                Thread.sleep(50);
            }
            int offers = leaders.requests("/eight/cluster/internal/offer-shared-object/").size();
            // before, node 9's heartbeat covered the offer to node 8, and node 8's next one counted as a new leader
            // again: a new offer, and a failed call, with each of its heartbeats. Now the one offer is kept for node 8
            // (node 9's heartbeats decide nothing about it) and sent again after 1, 2, 4 and 8 heartbeat intervals
            assertTrue(offers >= 1 && offers <= 5, offers + " offers to node 8 in 3 s");
        }
    }

    @Test
    void copyOfAPendingOfferThatIsLearnedToBeFailedOverMeanwhileIsNotSent() throws Throwable {
        var offerStatus = new java.util.concurrent.atomic.AtomicInteger(503);
        try (var leader = new StubServer()
                .respond("/leader/cluster/internal/heartbeat", 200, "8")
                .respond("/leader/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/leader/cluster/internal/offer-shared-object/", () -> 0, offerStatus::get, "[]")
                .respond("/leader", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(leader.url("/leader"));
            service.forceToFollower();
            // node 5 is gone; node 6 is heard (its copy is kept for the new leader, but not sent)
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            service.overwriteSharedObject(6, new ClusterService.MergeSharedObjectInfo(20, new HashMap<>(Map.of("v", 20))));
            String offer = "/leader/cluster/internal/offer-shared-object/";

            // a new leader (a half-healed split brain) lacks both: node 5's copy is offered, and the call fails
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(6, Position.FOLLOWER, 0, Map.of(6, 20L));
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                return !leader.requests(offer).isEmpty();
            }, () -> "no offer");
            assertTrue(leader.requests(offer).get(0).contains("\"5\":{\"v\":10}"), leader.requests(offer).get(0));
            int calls = leader.requests(offer).size();
            // the leader of this node's side finished that failover: its broadcast arrives before the offer got through
            service.replicaDeleted(5, 10L, false);
            offerStatus.set(200);
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(6, Position.FOLLOWER, 0, Map.of(6, 20L));
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                Thread.sleep(100);
            }
            // before, the offer still sent node 5's copy, and the new leader failed that object over a second time
            var later = leader.requests(offer).subList(calls, leader.requests(offer).size());
            assertTrue(later.stream().noneMatch(request -> request.contains("\"5\":")), later.toString());
            assertTrue(starter.getCluster().contains(6));
        }
    }

    @Test
    void offerToALeaderHeardAlternatelyWithAnotherIsKeptForItAndSentOnceItsNodeIsNoLongerHeard() throws Throwable {
        // this node is bridged to the other side of a split brain: node 8 leads there, and node 9 leads this side, whose
        // own object (with what it registered in the split brain) node 8 lacks
        try (var leaders = new StubServer()
                .respond("/eight/cluster/internal/heartbeat", 200, "8")
                .respond("/eight/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/eight/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/eight", 200, "")
                .respond("/nine", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(leaders.url("/eight"), leaders.url("/nine")));
            service.forceToFollower();
            service.overwriteSharedObject(9, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String offer = "/eight/cluster/internal/offer-shared-object/";

            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.LEADER, 6, Map.of(9, 10L));
                Thread.sleep(50);
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                Thread.sleep(50);
            }
            // node 9 is heard: its object is kept for node 8, but not sent
            assertEquals(List.of(), leaders.requests(offer));
            var copy = service.copyReplica(9, true);
            assertTrue(copy != null && Map.of("v", 10).equals(copy.obj), "node 9's copy: " + copy);

            // node 9 dies. Before, its heartbeat had dropped the offer to node 8 (node 9 lists its own object), and node 8,
            // heard lately, was not offered it again: what node 9 registered in the split brain was never failed over
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return !leaders.requests(offer).isEmpty();
            }, () -> "node 9's object not offered to node 8 once node 9 was gone: " + seq(starter));
            assertTrue(leaders.requests(offer).get(0).contains("\"9\":{\"v\":10}"), leaders.requests(offer).get(0));
        }
    }

    @Test
    void offerIsKeptForItsLeaderWhileAnotherOneElectedMeanwhileIsHeardAlternatelyWithIt() throws Throwable {
        // as above, but node 9 is elected only while this node is bridged: its first heartbeat as leader comes after node
        // 8's last one, while node 8 is heard every interval
        try (var leaders = new StubServer()
                .respond("/eight/cluster/internal/heartbeat", 200, "8")
                .respond("/eight/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/eight/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/eight", 200, "")
                .respond("/nine", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(leaders.url("/eight"), leaders.url("/nine")));
            service.forceToFollower();
            service.overwriteSharedObject(9, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            String offer = "/eight/cluster/internal/offer-shared-object/";

            // node 9 is heard as a follower, node 8 as leader: node 9's object is kept for node 8, but not sent
            long until = System.nanoTime() + Duration.ofMillis(600).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.FOLLOWER, 0, Map.of(9, 10L));
                Thread.sleep(50);
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                Thread.sleep(50);
            }
            // node 9 leads from now on (elected after node 8's last heartbeat here): it did not take over from node 8,
            // which is still heard
            long transitionTime = System.currentTimeMillis();
            until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(9, Position.LEADER, transitionTime, Map.of(9, 10L));
                Thread.sleep(50);
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                Thread.sleep(50);
            }
            assertEquals(List.of(), leaders.requests(offer));

            // node 9 dies: node 8 is offered its object. A rule that took any leader elected after the last heartbeat of
            // the one an offer goes to for its successor would have given node 9's first heartbeat the offer, which node 9
            // needs none of: node 8 would never have got it
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return !leaders.requests(offer).isEmpty();
            }, () -> "node 9's object not offered to node 8 once node 9 was gone: " + seq(starter));
            assertTrue(leaders.requests(offer).get(0).contains("\"9\":{\"v\":10}"), leaders.requests(offer).get(0));
        }
    }

    @Test
    void leaderRemembersAFailoverBroadcastAndDropsACopyOfThatObjectItTookOver() throws Throwable {
        var deleted = new CopyOnWriteArrayList<String>();
        var starter = startNode(1, 1, new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex + ":" + TestCluster.json(object))));
        var service = service(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        var client = starter.getNodeHttpClient();
        // a follower's offer of node 5's object, which crossed the broadcast of the (demoted) leader that failed it over
        client.call(internal(starter, "/offer-shared-object/2"), "POST", replicaOf(5, 10, 10), null);
        assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);
        client.call(internal(starter, "/cluster-deleted/5?seq=10"), "DELETE", null, null);
        // before, the leader ignored the broadcast, and failed that object over a second time
        assertNull(service.copyReplica(5, true));
        // nor is it taken over again
        client.call(internal(starter, "/offer-shared-object/2"), "POST", replicaOf(5, 10, 10), null);
        assertNull(service.copyReplica(5, true));
        Thread.sleep(2000);
        assertEquals(List.of(), deleted);
        // a newer object of node 5 is, and is failed over
        client.call(internal(starter, "/offer-shared-object/2"), "POST", replicaOf(5, 11, 11), null);
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.equals(List.of("5:{\"v\":11}")), () -> "newer object not failed over: " + deleted);
    }

    @Test
    void objectWhoseFailoverStillRanOnTheDemotedLeaderIsFailedOverByTheWinnerAfterTheGrace() throws Throwable {
        var deleted = new CopyOnWriteArrayList<String>();
        var starter = startNode(1, 1, new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex + ":" + TestCluster.json(object))));
        var service = service(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        var client = starter.getNodeHttpClient();
        // node 3, the demoted leader, is a member (heartbeats every 100 ms), whose own object this node holds
        service.overwriteSharedObject(3, new ClusterService.MergeSharedObjectInfo(7, new HashMap<>(Map.of("v", 7))));
        var node3Alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        var heartbeats = new Thread(() -> {
            while (node3Alive.get()) {
                service.heartbeatReceived(3, Position.FOLLOWER, 0, Map.of(3, 7L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        heartbeats.setDaemon(true);
        heartbeats.start();
        try {
            TestCluster.await(Duration.ofSeconds(2), () -> starter.getCluster().contains(3), () -> "node 3 not a member");

            // node 3 was failing node 5 over when it was demoted, and hands that object over with the rest (a node of the
            // previous development version marks it with ?removing=, which is ignored)
            client.call(internal(starter, "/sync-shared-object/3?handover=a&removing=5:10"), "POST", replicaOf(5, 10, 10), null);
            assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);
            // node 3 lives on and never broadcasts that failover: it follows this node, and holds the object again when
            // its failover finishes. Before, this node did not fail it over while node 3 was a member, so node 5's object
            // stayed on every node for good (and its devices, refused here meanwhile, ran nowhere)
            TestCluster.await(Duration.ofSeconds(5), () -> deleted.equals(List.of("5:{\"v\":10}")) && service.copyReplica(5, true) == null,
                    () -> "node 5 not failed over by the winner: " + deleted + ", held: " + seq(starter).keySet());
            assertTrue(starter.getCluster().contains(3));
        } finally {
            node3Alive.set(false);
        }
    }

    @Test
    void failoverThatFinishesAfterItsLeaderWasDemotedIsLeftToTheWinner() throws Throwable {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/heartbeat", 200, "9")
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", 200, "")
                .respond("/winner/cluster/internal/cluster-deleted/", 200, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, new ClusterEvents()
                    .clusterDeleted("slow", (nodeIndex, object) -> {
                        inFailover.countDown();
                        release.await();
                    }));
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            assertEquals(Position.LEADER, starter.getPosition());
            String sync = "/winner/cluster/internal/sync-shared-object/";
            // node 5 is lost: its failover is under way here
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            service.removeSharedObject(5);
            assertTrue(inFailover.await(5, TimeUnit.SECONDS), "no failover of node 5");

            // the heal: node 9's heartbeat demotes this node, which hands node 5's object over with the rest, and node 9
            // takes it over (it answers)
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            assertEquals(Position.FOLLOWER, starter.getPosition());
            TestCluster.await(Duration.ofSeconds(5), () -> winner.requests(sync).stream().anyMatch(request -> request.contains("\"5\":{\"v\":10}")),
                    () -> "node 5's object not handed over: " + winner.requests(sync));
            Thread.sleep(300);
            assertNull(service.copyReplica(5, false));
            // the failover finishes now, after the demotion. Round 5 broadcast it like any other, and the winner dropped the
            // object it had taken over without failing it over: the devices that this node could not place through the
            // winner meanwhile (registered there with that object) ran nowhere. Round 7 broadcast it marked as such, which
            // makes a leader keep what it holds, but made a follower drop a copy that this node had listed there, and this
            // node held none: had the winner died before its own failover, no node would have held that object. Now this
            // node holds the copy it handed over again, the winner's, as it would once the winner listed it, and does not
            // broadcast: the winner fails that object over itself, or the leader after it does
            release.countDown();
            TestCluster.await(Duration.ofSeconds(5), () -> service.copyReplica(5, false) != null, () -> "node 5's object not held again: " + seq(starter));
            assertEquals(Map.of("v", 10), service.copyReplica(5, false).obj);
            assertEquals(10L, service.copyReplica(5, false).seq);
            Thread.sleep(500);
            assertEquals(List.of(), winner.uris("/winner/cluster/internal/cluster-deleted/"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void failoverThatFinishesWhileTheHandoverCallCarryingItsObjectIsOnItsWayToTheWinnerLeavesThatObjectHeldHere() throws Throwable {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String sync = "/winner/cluster/internal/sync-shared-object/";
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/heartbeat", 200, "9")
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond(sync, 3000, 200, "")
                .respond("/winner/cluster/internal/cluster-deleted/", 200, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, new ClusterEvents()
                    .clusterDeleted("slow", (nodeIndex, object) -> {
                        inFailover.countDown();
                        release.await();
                    }));
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            assertEquals(Position.LEADER, starter.getPosition());
            // this node's heartbeats reach node 9
            TestCluster.await(Duration.ofSeconds(5), () -> !winner.requests("/winner/cluster/internal/heartbeat").isEmpty(), () -> "no heartbeat to node 9");
            Thread.sleep(300);
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            service.removeSharedObject(5);
            assertTrue(inFailover.await(5, TimeUnit.SECONDS), "no failover of node 5");

            // node 9 demotes this node, whose handover call, node 5's object with the rest, takes a while to be answered
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            assertEquals(Position.FOLLOWER, starter.getPosition());
            TestCluster.await(Duration.ofSeconds(5), () -> !winner.requests(sync).isEmpty(), () -> "no handover call");
            long arrived = System.nanoTime();
            assertTrue(winner.requests(sync).get(0).contains("\"5\":{\"v\":10}"), winner.requests(sync).get(0));
            // the failover finishes meanwhile: node 9 may have taken node 5's object over already, and refused what this
            // failover placed through it (the call's answer may come after that). This node holds its copy again, and does
            // not broadcast that failover
            release.countDown();
            TestCluster.await(Duration.ofSeconds(2), () -> service.copyReplica(5, false) != null, () -> "node 5's object not held again: " + seq(starter));
            assertTrue(System.nanoTime() - arrived < TimeUnit.MILLISECONDS.toNanos(3000), "held again only once the call was answered");
            assertEquals(Map.of("v", 10), service.copyReplica(5, false).obj);
            Thread.sleep(3500);
            assertEquals(List.of(), winner.uris("/winner/cluster/internal/cluster-deleted/"));
            assertEquals(Map.of("v", 10), service.copyReplica(5, false).obj);
        } finally {
            release.countDown();
        }
    }

    @Test
    void failoverThatFinishesBeforeAWinnerNotReachedByHeartbeatsAnswersTheHandoverIsBroadcastAndItsObjectHeldAgainOnTheAnswer() throws Throwable {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String sync = "/winner/cluster/internal/sync-shared-object/";
        String deleted = "/winner/cluster/internal/cluster-deleted/";
        // node 9 answers no heartbeat with its index: it is looked up, as a node of this version that does not hear this
        // node yet would be
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond(sync, 2000, 200, "")
                .respond(deleted, 200, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, new ClusterEvents()
                    .clusterDeleted("slow", (nodeIndex, object) -> {
                        inFailover.countDown();
                        release.await();
                    }));
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            assertEquals(Position.LEADER, starter.getPosition());
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            service.removeSharedObject(5);
            assertTrue(inFailover.await(5, TimeUnit.SECONDS), "no failover of node 5");

            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            assertEquals(Position.FOLLOWER, starter.getPosition());
            TestCluster.await(Duration.ofSeconds(5), () -> !winner.requests(sync).isEmpty(), () -> "no handover call");
            assertTrue(winner.requests(sync).get(0).contains("\"5\":{\"v\":10}"), winner.requests(sync).get(0));
            // the failover finishes before the answer: whether node 9 got node 5's object is not known yet, so it is
            // broadcast marked as such (as when the call does not get through, a one-way link)
            release.countDown();
            TestCluster.await(Duration.ofSeconds(1), () -> !winner.uris(deleted).isEmpty(), () -> "failover not broadcast");
            assertEquals(List.of(deleted + "5?seq=10&demoted=true&sender=1"), winner.uris(deleted));
            assertNull(service.copyReplica(5, false));
            // node 9 answers: it took node 5's object over, so this node holds its copy again
            TestCluster.await(Duration.ofSeconds(5), () -> service.copyReplica(5, false) != null, () -> "node 5's object not held again: " + seq(starter));
            assertEquals(Map.of("v", 10), service.copyReplica(5, false).obj);
            assertEquals(List.of(deleted + "5?seq=10&demoted=true&sender=1"), winner.uris(deleted));
        } finally {
            release.countDown();
        }
    }

    /**
     * this node leads and fails node 5 over (slowly) when node 9 demotes it; its handover, node 5's object with the rest
     * (node 6's, if given), does not get through until that failover has finished after the demotion, nor does the
     * broadcast of that failover
     * @return the "path?query" and "path body" of the last handover call, and the "path?query" of the broadcast of that
     *         failover
     */
    private List<String> handoverSentAgainAfterTheFailoverOfACopyFinished(boolean withNode6) throws Throwable {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var syncStatus = new java.util.concurrent.atomic.AtomicInteger(503);
        try (var winner = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", () -> 0, syncStatus::get, "")
                .respond("/winner/cluster/internal/cluster-deleted/", 503, "")
                .respond("/winner", 200, "")) {
            var starter = startNode(1, 1, new ClusterEvents()
                    .clusterDeleted("slow", (nodeIndex, object) -> {
                        inFailover.countDown();
                        release.await();
                    }));
            var service = service(starter);
            starter.nodeTargetUrls.add(winner.url("/winner"));
            assertEquals(Position.LEADER, starter.getPosition());
            String sync = "/winner/cluster/internal/sync-shared-object/";
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            // node 6 died where only this node heard it
            if (withNode6)
                service.overwriteSharedObject(6, new ClusterService.MergeSharedObjectInfo(20, new HashMap<>(Map.of("v", 20))));
            service.removeSharedObject(5);
            assertTrue(inFailover.await(5, TimeUnit.SECONDS), "no failover of node 5");

            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            assertEquals(Position.FOLLOWER, starter.getPosition());
            TestCluster.await(Duration.ofSeconds(5), () -> !winner.requests(sync).isEmpty(), () -> "no handover call");
            assertTrue(winner.requests(sync).get(0).contains("\"5\":{\"v\":10}"), winner.requests(sync).get(0));
            release.countDown();
            TestCluster.await(Duration.ofSeconds(5), () -> !winner.requests("/winner/cluster/internal/cluster-deleted/").isEmpty(),
                    () -> "failover not broadcast");
            syncStatus.set(200);
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return winner.requests(sync).size() >= 2;
            }, () -> "handover not sent again");
            Thread.sleep(300);
            var calls = winner.requests(sync);
            var uris = winner.uris(sync);
            assertEquals(2, calls.size(), "handover calls: " + calls);
            return List.of(uris.get(1), calls.get(1), winner.uris("/winner/cluster/internal/cluster-deleted/").get(0));
        } finally {
            release.countDown();
        }
    }

    @Test
    void handoverAfterTheFailoverOfACopyFinishedCarriesTheSequenceRemovedInsteadOfThatCopy() throws Throwable {
        var call = handoverSentAgainAfterTheFailoverOfACopyFinished(true);
        // before, node 5's object went again, and the winner took it over and failed it over a second time. Now the
        // sequence removed goes instead, which the winner remembers: it declines that object from then on, also from a
        // follower on this side that missed the broadcast
        assertTrue(call.get(1).contains("\"6\":{\"v\":20}") && !call.get(1).contains("\"5\":"), call.get(1));
        assertTrue(call.get(0).endsWith("&removed=5:10"), call.get(0));
        // no call carrying node 5's object got through before its failover finished, so the winner did not take it over
        // from this node: that failover is broadcast, marked as finished after the demotion, with this node's index
        assertEquals("/winner/cluster/internal/cluster-deleted/5?seq=10&demoted=true&sender=1", call.get(2));
    }

    @Test
    void winnerToldWithoutTheObjectsLearnsOfTheFailoverOfACopyThatFinishedAfterTheDemotion() throws Throwable {
        var call = handoverSentAgainAfterTheFailoverOfACopyFinished(false);
        // everything handed over is held by the winner or removed here, so it is only told. Before, it never learned that
        // node 5's object was failed over, and a follower's offer of it made it fail that object over a second time
        assertTrue(call.get(1).contains("\"sharedObject\":{}") && call.get(1).contains("\"sharedObjectSeq\":{}"), call.get(1));
        assertTrue(call.get(0).endsWith("&removed=5:10"), call.get(0));
    }

    @Test
    void leaderKeepsWhatItHoldsOnTheBroadcastOfADemotedLeaderAndTakesThatObjectOverNoMore() throws Throwable {
        var deleted = new CopyOnWriteArrayList<String>();
        var starter = startNode(1, 1, new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex + ":" + TestCluster.json(object))));
        var service = service(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        var client = starter.getNodeHttpClient();
        // node 5's object, taken over from a follower's offer while node 3, demoted meanwhile, still failed it over
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(5, 10, 10), null);
        // that failover finished on node 3 after its demotion, and may have placed nothing: what it placed through this
        // node was registered here with that copy. Round 4 dropped the copy here, and node 5's devices ran nowhere
        client.call(internal(starter, "/cluster-deleted/5?seq=10&demoted=true"), "DELETE", null, null);
        assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);
        // node 6's failover finished on node 3 before any copy of it came here: a follower that missed that broadcast, or
        // a handover sent before it, brings it here no more (round 5 took it over, and failed it over a second time)
        client.call(internal(starter, "/cluster-deleted/6?seq=20&demoted=true"), "DELETE", null, null);
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(6, 20, 20), null);
        client.call(internal(starter, "/sync-shared-object/3?handover=a"), "POST", replicaOf(6, 20, 20), null);
        assertNull(service.copyReplica(6, true));
        // node 7's failover finished there too: a handover that tells so (without the objects) has the same effect
        client.call(internal(starter, "/sync-shared-object/3?handover=a&removed=7:30"), "POST", new ClusterService.SharedObject(Map.of(), Map.of()), null);
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(7, 30, 30), null);
        assertNull(service.copyReplica(7, true));

        // node 5's copy is failed over here, once
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.contains("5:{\"v\":10}"), () -> "node 5 not failed over: " + deleted);
        Thread.sleep(2000);
        assertEquals(List.of("5:{\"v\":10}"), deleted);
        // a newer object of node 6 is taken over, and failed over
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(6, 21, 21), null);
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.contains("6:{\"v\":21}"), () -> "newer object of node 6 not failed over: " + deleted);
    }

    @Test
    void followerDropsACopyOnTheBroadcastOfADemotedLeaderAndOffersItToNoLeader() throws Throwable {
        var offerStatus = new java.util.concurrent.atomic.AtomicInteger(503);
        try (var leader = new StubServer()
                .respond("/leader/cluster/internal/heartbeat", 200, "8")
                .respond("/leader/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/leader/cluster/internal/offer-shared-object/", () -> 0, offerStatus::get, "[]")
                .respond("/leader", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.add(leader.url("/leader"));
            service.forceToFollower();
            // node 5 died on this side of a split brain, whose leader (node 3) fails it over; node 6 is heard
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            service.overwriteSharedObject(6, new ClusterService.MergeSharedObjectInfo(20, new HashMap<>(Map.of("v", 20))));
            String offer = "/leader/cluster/internal/offer-shared-object/";

            // a one-way heal: node 8, the winner, is heard but not reached. Node 5's copy is offered to it, in vain
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(6, Position.FOLLOWER, 0, Map.of(6, 20L));
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                return !leader.requests(offer).isEmpty();
            }, () -> "no offer");
            int calls = leader.requests(offer).size();
            // node 3's failover of node 5 finishes after node 8 demoted it: round 5 did not broadcast it, and this node's
            // offer made node 8 fail node 5 over a second time. Now it is broadcast, marked as such: dropped here
            starter.getNodeHttpClient().call(internal(starter, "/cluster-deleted/5?seq=10&demoted=true"), "DELETE", null, null);
            assertNull(service.copyReplica(5, true));
            offerStatus.set(200);
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < until) {
                service.heartbeatReceived(6, Position.FOLLOWER, 0, Map.of(6, 20L));
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L));
                Thread.sleep(100);
            }
            var later = leader.requests(offer).subList(calls, leader.requests(offer).size());
            assertTrue(later.stream().noneMatch(request -> request.contains("\"5\":")), later.toString());
            assertNull(service.copyReplica(5, true));
        }
    }

    @Test
    void followerKeepsACopyThatTheDemotedLeaderDidNotListOnItsBroadcastAndOffersItToTheLeaderAfterTheWinner() throws Throwable {
        var json = new ObjectMapper();
        try (var leaders = new StubServer()
                .respond("/winner/cluster/internal/node-status", 200, status(8, Position.LEADER))
                .respond("/winner/cluster/internal/shared-object/5", 200, json.writeValueAsString(new ClusterService.MergeSharedObjectInfo(10, Map.of("v", 10))))
                .respond("/winner/cluster/internal/offer-shared-object/", 503, "")
                .respond("/winner", 200, "")
                .respond("/next/cluster/internal/heartbeat", 200, "7")
                .respond("/next/cluster/internal/node-status", 200, status(7, Position.LEADER))
                .respond("/next/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/next", 200, "")) {
            var starter = startNode(1, 1, null);
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(leaders.url("/winner"), leaders.url("/next")));
            service.forceToFollower();
            // this node followed node 3, a split-brain leader that listed node 6's object (node 6 died on that side)
            service.overwriteSharedObject(6, new ClusterService.MergeSharedObjectInfo(20, new HashMap<>(Map.of("v", 20))));
            service.heartbeatReceived(3, Position.LEADER, 3, Map.of(6, 20L));
            // node 8 won the split brain, and took node 5's object over from node 3, demoted, whose failover of node 5
            // still ran: this node gets node 8's copy. It offers node 6's copy to node 8, in vain (a one-way link)
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L, 5, 10L));
                return service.copyReplica(5, true) != null;
            }, () -> "node 5 not fetched");

            // node 3's failovers of both finish after its demotion. That of node 5 may have placed nothing: node 8 refused
            // what node 3 connected through it while it held node 5's object. Round 6 dropped node 5's copy here too, so had
            // node 8 died before failing it over itself, no node would have held that object any more
            var client = starter.getNodeHttpClient();
            client.call(internal(starter, "/cluster-deleted/5?seq=10&demoted=true&sender=3"), "DELETE", null, null);
            client.call(internal(starter, "/cluster-deleted/6?seq=20&demoted=true&sender=3"), "DELETE", null, null);
            assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);
            // the copy node 3 listed is dropped, as in round 6: no leader held it, so that failover could place its devices
            assertNull(service.copyReplica(6, true));
            // kept while node 8 lists it
            service.heartbeatReceived(8, Position.LEADER, 5, Map.of(8, 1L, 5, 10L));
            assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);

            // node 8 dies before its own failover of node 5, and node 7, elected after it, lacks that object: offered to it
            long transitionTime = System.currentTimeMillis();
            String offer = "/next/cluster/internal/offer-shared-object/";
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(7, Position.LEADER, transitionTime, Map.of(7, 1L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return !leaders.requests(offer).isEmpty();
            }, () -> "node 5's object not offered to node 7: " + seq(starter));
            assertTrue(leaders.requests(offer).get(0).contains("\"5\":{\"v\":10}"), leaders.requests(offer).get(0));
            assertTrue(leaders.requests(offer).stream().noneMatch(request -> request.contains("\"6\":")), leaders.requests(offer).toString());
        }
    }

    @Test
    void nodeLeadingAfterItLearnedOfADemotedLeadersFailoverAsFollowerTakesThatObjectOverAndFailsItOver() throws Throwable {
        var deleted = new CopyOnWriteArrayList<String>();
        var starter = startNode(1, 1, new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex + ":" + TestCluster.json(object))));
        var service = service(starter);
        var client = starter.getNodeHttpClient();
        service.forceToFollower();
        assertEquals(Position.FOLLOWER, starter.getPosition());
        // node 3's failover of node 5 finished after its demotion; this follower holds no copy of node 5's object
        client.call(internal(starter, "/cluster-deleted/5?seq=10&demoted=true&sender=3"), "DELETE", null, null);
        // the winner, which held that object and was to fail it over itself, died first. This node leads now, and a
        // follower that kept its copy offers it. Round 6 declined it, as failed over already: node 3's failover may have
        // placed nothing, and node 5's devices ran nowhere
        service.forceToLeader();
        assertEquals(Position.LEADER, starter.getPosition());
        client.call(internal(starter, "/offer-shared-object/2"), "POST", replicaOf(5, 10, 10), null);
        assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.contains("5:{\"v\":10}"), () -> "node 5 not failed over: " + deleted);
        Thread.sleep(1500);
        assertEquals(List.of("5:{\"v\":10}"), deleted);
        // learned while leading, such a failover makes the leader decline that object (see
        // leaderKeepsWhatItHoldsOnTheBroadcastOfADemotedLeaderAndTakesThatObjectOverNoMore)
        client.call(internal(starter, "/cluster-deleted/6?seq=20&demoted=true&sender=3"), "DELETE", null, null);
        client.call(internal(starter, "/offer-shared-object/2"), "POST", replicaOf(6, 20, 20), null);
        assertNull(service.copyReplica(6, true));
    }

    @Test
    void demotedNodeOffersTheWinnersCopyOfAnObjectItFailedOverAfterItsDemotionToTheLeaderAfterTheWinner() throws Throwable {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var json = new ObjectMapper();
        try (var peers = new StubServer()
                .respond("/winner/cluster/internal/heartbeat", 200, "9")
                .respond("/winner/cluster/internal/node-status", 200, status(9, Position.LEADER))
                .respond("/winner/cluster/internal/sync-shared-object/", 200, "")
                .respond("/winner/cluster/internal/shared-object/5", 200, json.writeValueAsString(new ClusterService.MergeSharedObjectInfo(10, Map.of("v", 10))))
                .respond("/winner", 200, "")
                .respond("/next/cluster/internal/heartbeat", 200, "7")
                .respond("/next/cluster/internal/node-status", 200, status(7, Position.LEADER))
                .respond("/next/cluster/internal/offer-shared-object/", 200, "[]")
                .respond("/next", 200, "")) {
            var starter = startNode(1, 1, new ClusterEvents()
                    .clusterDeleted("slow", (nodeIndex, object) -> {
                        inFailover.countDown();
                        release.await();
                    }));
            var service = service(starter);
            starter.nodeTargetUrls.addAll(List.of(peers.url("/winner"), peers.url("/next")));
            assertEquals(Position.LEADER, starter.getPosition());
            // node 5 is lost: its failover is under way here
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
            service.removeSharedObject(5);
            assertTrue(inFailover.await(5, TimeUnit.SECONDS), "no failover of node 5");

            // node 9 demotes this node and takes node 5's object over from its handover; this node gets node 9's copy back
            service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L));
            assertEquals(Position.FOLLOWER, starter.getPosition());
            TestCluster.await(Duration.ofSeconds(5), () -> !peers.requests("/winner/cluster/internal/sync-shared-object/").isEmpty(),
                    () -> "no handover call");
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(9, Position.LEADER, 0, Map.of(9, 1L, 5, 10L));
                return service.copyReplica(5, false) != null;
            }, () -> "node 9's copy of node 5 not fetched");
            // this node's failover finishes now. What it placed through node 9 was refused while node 9 held node 5's
            // object, so it may have placed nothing: node 9 fails that object over itself
            release.countDown();
            Thread.sleep(300);
            assertEquals(Map.of("v", 10), service.copyReplica(5, true).obj);

            // node 9 dies before, and node 7, elected after it, lacks node 5's object. Round 6 took this node's own failover
            // for done and never offered that copy: node 7's first heartbeat dropped it, and node 5's devices ran nowhere
            long transitionTime = System.currentTimeMillis();
            String offer = "/next/cluster/internal/offer-shared-object/";
            TestCluster.await(Duration.ofSeconds(5), () -> {
                service.heartbeatReceived(7, Position.LEADER, transitionTime, Map.of(7, 1L));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return !peers.requests(offer).isEmpty();
            }, () -> "node 5's object not offered to node 7: " + seq(starter));
            assertTrue(peers.requests(offer).get(0).contains("\"5\":{\"v\":10}"), peers.requests(offer).get(0));
        } finally {
            release.countDown();
        }
    }

    @Test
    void offeredCopyOfANodeWhoseRemovalWasRequestedLatelyIsFailedOverRightAway() throws Throwable {
        var deleted = new java.util.concurrent.ConcurrentHashMap<Integer, Long>();
        var starter = startNode(1, 1, new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> deleted.put(nodeIndex, System.nanoTime())));
        assertEquals(Position.LEADER, starter.getPosition());
        var client = starter.getNodeHttpClient();
        // a follower whose timer for node 5 expired asks this node, just elected, to remove it: it holds no copy yet
        client.call(internal(starter, "/remove-shared-object/5"), "DELETE", null, null);
        // then that follower's offer of node 5's object comes (the winner it had handed the object over to died). Before,
        // the copy waited for an absence grace of its own (leader-lost timeout plus a heartbeat interval)
        long offered = System.nanoTime();
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(5, 10, 10), null);
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.containsKey(5), () -> "node 5 not failed over");
        long elapsedMillis = (deleted.get(5) - offered) / 1_000_000;
        assertTrue(elapsedMillis < 700, "node 5 failed over " + elapsedMillis + " ms after its object was offered");

        // a request older than the grace does not count
        client.call(internal(starter, "/remove-shared-object/6"), "DELETE", null, null);
        Thread.sleep(1700);
        offered = System.nanoTime();
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(6, 20, 20), null);
        Thread.sleep(700);
        assertFalse(deleted.containsKey(6), "node 6 failed over without its absence grace");
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.containsKey(6), () -> "node 6 not failed over");
        assertTrue((deleted.get(6) - offered) / 1_000_000 >= 1200, "node 6 failed over before its absence grace");

        // nor while another leader is heard (transient dual leaders): it may be failing node 7 over too, and its broadcast
        // makes this node drop a copy it took over, as long as that copy waits for its grace
        service(starter).heartbeatReceived(9, Position.LEADER, System.currentTimeMillis(), Map.of(9, 1L));
        assertEquals(Position.LEADER, starter.getPosition());
        client.call(internal(starter, "/remove-shared-object/7"), "DELETE", null, null);
        client.call(internal(starter, "/offer-shared-object/4"), "POST", replicaOf(7, 30, 30), null);
        Thread.sleep(700);
        assertFalse(deleted.containsKey(7), "node 7 failed over while another leader was heard");
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.containsKey(7), () -> "node 7 not failed over");
    }

    @Test
    void aFollowersChangesAreNotSentBackToIt() throws Throwable {
        var json = new ObjectMapper();
        try (var peers = new StubServer()
                .respond("/two/cluster/internal/heartbeat", 200, "2")
                .respond("/two/cluster/internal/node-status", 200, status(2, Position.FOLLOWER))
                .respond("/two/cluster/internal/shared-object", 200, json.writeValueAsString(new ClusterService.MergeSharedObjectInfo(6, Map.of("k", 1))))
                .respond("/two/cluster/internal/check-shared-object-changes", 200, APPLIED)
                .respond("/two", 200, "true")
                .respond("/three/cluster/internal/heartbeat", 200, "3")
                .respond("/three/cluster/internal/check-shared-object-changes", 200, APPLIED)
                .respond("/three", 200, "true")) {
            var starter = startLeader();
            starter.nodeTargetUrls.addAll(List.of(peers.url("/two"), peers.url("/three")));
            TestCluster.await(Duration.ofSeconds(5), () -> peers.requests("/two/cluster/internal/heartbeat").size() >= 2
                    && peers.requests("/three/cluster/internal/heartbeat").size() >= 2, () -> "no heartbeat");
            var service = service(starter);

            // node 2's changes: the leader pulls its object first (it holds none yet), then applies the next one
            assertNull(service.setSharedObjectToLeader(2, new ClusterService.MergeSharedObjectInfo(6, Map.of("k", 1))));
            assertNull(service.setSharedObjectToLeader(2, new ClusterService.MergeSharedObjectInfo(7, Map.of("k", 2))));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                try {
                    return batchedSequences(peers, "/three").contains(7L);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, () -> "changes not propagated");
            Thread.sleep(300);
            assertEquals(List.of(6L, 7L), batchedSequences(peers, "/three"));
            // before, each change went back to node 2 as well, which has it and answered without doing anything
            assertEquals(List.of(), peers.requests("/two" + CHANGES));
        }
    }

    @Test
    void removalsLearnedAsFollowerMakeTheNodeDeclineStaleOffersOnceItLeads() throws Throwable {
        var starter = startNode(1, 1, null);
        var service = service(starter);
        service.forceToFollower();
        assertEquals(Position.FOLLOWER, starter.getPosition());
        // the leader's broadcast: node 5's object at sequence 10 was failed over
        service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
        service.replicaDeleted(5, 10L, false);
        assertNull(service.copyReplica(5, true));
        // the leader holds no replica of node 6 any more (a fetch answered 404)
        service.overwriteSharedObject(6, new ClusterService.MergeSharedObjectInfo(20, new HashMap<>(Map.of("v", 20))));
        assertTrue(service.applyFetched(6, 20L, null));

        service.forceToLeader();
        assertEquals(Position.LEADER, starter.getPosition());
        // a follower that missed those removals offers them: declined, they were failed over already
        service.adoptOffered(2, new ClusterService.SharedObject(Map.of(5, new HashMap<>(Map.of("v", 10)), 6, new HashMap<>(Map.of("v", 20))),
                Map.of(5, 10L, 6, 20L)));
        assertNull(service.copyReplica(5, true));
        assertNull(service.copyReplica(6, true));
        // a member's object comes from the member itself, not from an offer
        service.heartbeatReceived(8, Position.FOLLOWER, 0, null);
        service.adoptOffered(2, new ClusterService.SharedObject(Map.of(8, new HashMap<>(Map.of("v", 8))), Map.of(8, 3L)));
        assertNull(service.copyReplica(8, true));
        // a newer object of a node that is gone is taken over
        service.adoptOffered(2, new ClusterService.SharedObject(Map.of(5, new HashMap<>(Map.of("v", 11))), Map.of(5, 11L)));
        assertEquals(Map.of("v", 11), service.copyReplica(5, true).obj);
    }

    @Test
    void splitBrainSyncTakesOverOnlyNewerObjectsOfNodesFailedOverAlready() throws Throwable {
        var deleted = new CopyOnWriteArrayList<String>();
        var starter = startNode(0, 0, new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex + ":" + TestCluster.json(object))));
        var service = service(starter);
        service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(10, new HashMap<>(Map.of("v", 10))));
        service.removeSharedObject(5);
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.equals(List.of("5:{\"v\":10}")) && service.copyReplica(5, true) == null,
                () -> "node 5 not removed: " + deleted);

        // a demoted leader that could hand its copies over only now: the object failed over already is not taken over again
        service.syncSharedObject(new ClusterService.SharedObject(Map.of(5, new HashMap<>(Map.of("v", 10))), Map.of(5, 10L)));
        assertNull(service.copyReplica(5, true));
        // a newer one is, and failed over in turn
        service.syncSharedObject(new ClusterService.SharedObject(Map.of(5, new HashMap<>(Map.of("v", 11))), Map.of(5, 11L)));
        TestCluster.await(Duration.ofSeconds(5), () -> deleted.contains("5:{\"v\":11}"), () -> "newer object of node 5 not failed over: " + deleted);
        assertEquals(2, deleted.size());
    }

    @Test
    void disposedNodeIgnoresHeartbeatsAndRemovalsStillUnderWay() throws Throwable {
        var added = new CopyOnWriteArrayList<Integer>();
        var deleted = new CopyOnWriteArrayList<Integer>();
        var starter = startNode(1, 1, new ClusterEvents()
                .clusterAdded("record", added::add)
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(nodeIndex)));
        var service = service(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        starter.dispose();

        // a heartbeat handled, and a removal requested, after the node was disposed
        service.heartbeatReceived(2, Position.FOLLOWER, 0, Map.of(2, 7L));
        service.overwriteSharedObject(3, new ClusterService.MergeSharedObjectInfo(7, new HashMap<>(Map.of("k", "v"))));
        service.removeSharedObject(3);
        // before, node 2 was added again, and removed (failover included) once its heartbeats stayed away
        Thread.sleep(2000);
        assertEquals(Set.of(), starter.getCluster());
        assertEquals(List.of(), added);
        assertEquals(List.of(), deleted);

        // started again, it handles them
        service.start();
        service.heartbeatReceived(2, Position.FOLLOWER, 0, null);
        assertTrue(starter.getCluster().contains(2));
        TestCluster.await(Duration.ofSeconds(5), () -> added.contains(2), () -> "node 2 not added after the restart");
    }

    @Test
    void transientSyncFailureAnswers503AndMalformedBody400() throws Throwable {
        var starter = startLeader();
        var client = starter.getNodeHttpClient();
        // sequence gap from node 3, which cannot be reached to fetch its shared-object
        var unavailable = assertThrows(NodeHttpClient.CallException.class, () -> client.call(internal(starter, "/merge-shared-object-to-leader/3"),
                "POST", new ClusterService.MergeSharedObjectInfo(5, Map.of("k", "v")), null));
        assertEquals(503, unavailable.getStatusCode());
        assertFalse(unavailable.isDeterministic());

        var malformed = assertThrows(NodeHttpClient.CallException.class, () ->
                client.callRaw(internal(starter, "/merge-shared-object-to-leader/3"), "POST", "{not json"));
        assertEquals(400, malformed.getStatusCode());
        assertTrue(malformed.isDeterministic());
    }

    @Test
    void activationCountsJoiningNodeAndFailoverSeesDeactivation() throws Throwable {
        var node = new AtomicReference<ClusterStarter>();
        var deleted = new CountDownLatch(1);
        var activatedWhenDeleted = new AtomicReference<Boolean>();
        var deletedObject = new AtomicReference<Map<String, Object>>();
        var events = new ClusterEvents().clusterDeleted("test", (nodeIndex, obj) -> {
            if (nodeIndex == 2) {
                activatedWhenDeleted.set(node.get().isActivated());
                deletedObject.set(obj);
                deleted.countDown();
            }
        });
        // quorum 2; node 2 counts as lost 1 s after its last heartbeat
        var starter = startNode(2, 1, events);
        node.set(starter);
        assertEquals(Position.LEADER, starter.getPosition());
        assertFalse(starter.isActivated());

        // node 2 is played by a stub that answers the status probe and serves its own shared-object to the leader's pull
        var json = new ObjectMapper();
        try (var node2 = new StubServer()
                .respond("/node2/cluster/internal/node-status", 200, json.writeValueAsString(new NodeStatus(2, Position.FOLLOWER, true)))
                .respond("/node2/cluster/internal/shared-object", 200, json.writeValueAsString(new ClusterService.MergeSharedObjectInfo(7, Map.of("k", "v"))))
                .respond("/node2", 200, "")) {
            starter.nodeTargetUrls.add(node2.url("/node2"));
            var client = starter.getNodeHttpClient();
            client.call(internal(starter, "/heartbeat"), "PUT",
                    new ClusterInternalClient.HeartbeatRequest(2, Position.FOLLOWER, 0, Map.of(2, 7L)), null);
            assertEquals(Set.of(1, 2), starter.getCluster());
            assertTrue(starter.isActivated(), "the joining node must count towards the quorum");

            // no further heartbeat: node 2 is deleted, and the cluster-deleted event (failover) runs already inactivated
            assertTrue(deleted.await(10, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, activatedWhenDeleted.get());
            assertEquals(Map.of("k", "v"), deletedObject.get());
            assertEquals(Set.of(1), starter.getCluster());
            assertFalse(starter.isActivated());
        }
    }

    @Test
    void lateChangeOfUnknownSenderCreatesNoReplica() throws Throwable {
        var starter = startLeader();
        var client = starter.getNodeHttpClient();
        // node 5 cannot be reached, so its object cannot be pulled: no empty placeholder takes its place
        var e = assertThrows(NodeHttpClient.CallException.class, () -> client.call(internal(starter, "/merge-shared-object-to-leader/5"),
                "POST", new ClusterService.MergeSharedObjectInfo(1, Map.of("k", "v")), null));
        assertEquals(503, e.getStatusCode());
        assertEquals(Set.of(1), seq(starter).keySet());
        assertEquals(Set.of(1), starter.getSharedObjectMap().keySet());
        // a replica that is not held answers 404 instead of null fields
        var absent = assertThrows(NodeHttpClient.CallException.class, () -> client.call(internal(starter, "/shared-object/5"), "GET", null, null));
        assertEquals(404, absent.getStatusCode());
    }

    @Test
    void guardedChangesApplyOnlyWhileTheGuardHolds() throws Throwable {
        var starter = startLeader();
        long base = seq(starter).get(1);
        starter.mergeSharedObject(Map.of("dev", Map.of("id", "dev")));
        assertTrue(starter.mergeSharedObjectIf(own -> own.containsKey("dev"), 5, "dev", "data", "x"));
        assertFalse(starter.mergeSharedObjectIf(own -> own.containsKey("gone"), 6, "gone", "data", "x"));
        assertEquals(Map.of("dev", Map.of("id", "dev", "data", Map.of("x", 5))), starter.getSharedObject());
        assertEquals(base + 2, seq(starter).get(1));

        assertFalse(starter.deleteSharedObjectIf(own -> own.containsKey("gone"), List.of(List.of("dev", "data"))));
        assertEquals(base + 2, seq(starter).get(1));
        assertTrue(starter.deleteSharedObjectIf(own -> own.containsKey("dev"), List.of(List.of("dev", "data"))));
        assertEquals(Map.of("dev", Map.of("id", "dev")), starter.getSharedObject());
        assertEquals(base + 3, seq(starter).get(1));
        assertEquals(Set.of("dev"), starter.readSharedObject(map -> new HashSet<>(map.get(1).keySet())));
        assertEquals(0, starter.getLeaderLostTimeoutSeconds());
    }
}
