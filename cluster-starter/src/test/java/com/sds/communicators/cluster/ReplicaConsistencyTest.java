package com.sds.communicators.cluster;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sds.communicators.cluster.support.NodeHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Replica updates stay atomic, copies stay detached, and sequence numbers never repeat across a restart. */
class ReplicaConsistencyTest {
    private static final JavaType ANY = TypeFactory.defaultInstance().constructType(Object.class);

    /** a node that is built but not started: only its replica handling is used */
    private static ClusterStarter builtNode() throws Exception {
        int port = StubServer.freePort();
        return ClusterStarter.builder(Set.of("http://127.0.0.1:" + port), port, 1).build();
    }

    private static ClusterService service(ClusterStarter starter) throws Exception {
        var field = ClusterStarter.class.getDeclaredField("clusterService");
        field.setAccessible(true);
        return (ClusterService) field.get(starter);
    }

    /** the change with sequence base + i sets v and one of 16 slots */
    private static Map<String, Object> delta(int i) {
        return Map.of("v", i, "k" + (i % 16), i);
    }

    /** the owner's object after changes 1..n */
    private static Map<String, Object> truth(int n) {
        var object = new HashMap<String, Object>();
        for (int i = Math.max(1, n - 15); i <= n; i++)
            object.put("k" + (i % 16), i);
        if (n > 0)
            object.put("v", n);
        return object;
    }

    @Test
    void staleFetchIsDroppedAndAbsentReplicaIsNotRecreated() throws Exception {
        var starter = builtNode();
        try {
            var service = service(starter);
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(100, new HashMap<>()));
            // a heartbeat decided to fetch at sequence 100; the next change arrives before the fetched copy
            assertTrue(service.checkSharedObject(5, new ClusterService.MergeSharedObjectInfo(101, Map.of("a", 1))));
            assertFalse(service.applyFetched(5, 100L, new ClusterService.MergeSharedObjectInfo(100, new HashMap<>())));
            var replica = service.copyReplica(5, false);
            assertEquals(101, replica.seq);
            assertEquals(Map.of("a", 1), replica.obj);

            // no replica: a change is refused (the leader then sends a snapshot) instead of landing on an empty object
            assertFalse(service.checkSharedObject(6, new ClusterService.MergeSharedObjectInfo(1, Map.of("x", 1))));
            assertNull(service.copyReplica(6, false));
            assertFalse(service.copySharedObjectSeq().containsKey(6));

            // the leader holds none any more: dropped (object and sequence together), but only if unchanged meanwhile
            assertFalse(service.applyFetched(5, 100L, null));
            assertTrue(service.applyFetched(5, 101L, null));
            assertNull(service.copyReplica(5, false));
            assertFalse(service.copySharedObjectSeq().containsKey(5));
        } finally {
            starter.dispose();
        }
    }

    @Test
    void concurrentChangesAndFetchedCopiesNeverPairASequenceWithOtherContent() throws Exception {
        var starter = builtNode();
        try {
            var service = service(starter);
            long base = 1000;
            int changes = 20000;
            service.overwriteSharedObject(5, new ClusterService.MergeSharedObjectInfo(base, new HashMap<>()));
            var done = new AtomicBoolean(false);
            var violations = new ConcurrentLinkedQueue<String>();

            var deltas = new Thread(() -> {
                for (int i = 1; i <= changes; i++)
                    service.checkSharedObject(5, new ClusterService.MergeSharedObjectInfo(base + i, delta(i)));
            });
            // the heartbeat fetch: decided at the sequence seen, applied later with what the leader had by then
            var fetches = new Thread(() -> {
                var random = new Random(1);
                while (!done.get()) {
                    var seen = service.copyReplica(5, false);
                    int n = (int) (seen.seq - base);
                    if (n >= changes)
                        break;
                    if (random.nextInt(4) == 0)
                        Thread.yield();
                    service.applyFetched(5, seen.seq, new ClusterService.MergeSharedObjectInfo(base + n + 1, truth(n + 1)));
                    // a copy older than the replica is always refused
                    if (n > 0 && service.applyFetched(5, seen.seq - 1, new ClusterService.MergeSharedObjectInfo(base + n - 1, truth(n - 1))))
                        violations.add("stale copy applied at " + n);
                }
            });
            var reader = new Thread(() -> {
                while (!done.get()) {
                    var copy = service.copyReplica(5, false);
                    var expected = truth((int) (copy.seq - base));
                    if (!expected.equals(copy.obj))
                        violations.add("seq " + copy.seq + " holds " + copy.obj + ", expected " + expected);
                }
            });
            reader.start();
            fetches.start();
            deltas.start();
            deltas.join(30000);
            done.set(true);
            fetches.join(10000);
            reader.join(10000);

            assertTrue(violations.isEmpty(), violations.size() + " violations, first: " + violations.peek());
            var last = service.copyReplica(5, false);
            assertEquals(base + changes, last.seq);
            assertEquals(truth(changes), last.obj);
        } finally {
            starter.dispose();
        }
    }

    @Test
    void routesAndCopiesStayConsistentUnderConcurrentWrites() throws Exception {
        try (var cluster = new TestCluster(2, 200, 1, 1, null)) {
            cluster.startAll();
            var leader = cluster.node(1);
            var follower = cluster.node(2);
            var failures = new ConcurrentLinkedQueue<String>();
            var stop = new AtomicBoolean(false);

            List<Thread> writers = new ArrayList<>();
            for (int node = 1; node <= 2; node++) {
                var starter = cluster.node(node);
                String prefix = node == 1 ? "k" : "f";
                writers.add(new Thread(() -> {
                    for (int i = 0; i < 300; i++) {
                        // new keys, so that the maps keep growing (resizing) while they are read
                        starter.mergeSharedObject(Map.of(prefix + i, Map.of("v", i, "list", List.of(i))));
                        if (i % 10 == 9)
                            starter.deleteSharedObject(prefix + (i - 5));
                    }
                }));
            }
            var paths = List.of("/cluster/shared-object-map", "/cluster/shared-object-seq", "/cluster/internal/shared-object",
                    "/cluster/internal/shared-object/1", "/cluster/internal/shared-object/2", "/cluster/internal/shared-object-digest",
                    "/cluster/internal/shared-object-digest?own=true");
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readers.add(new Thread(() -> {
                    var client = leader.getNodeHttpClient();
                    while (!stop.get()) {
                        for (var starter : List.of(leader, follower)) {
                            for (var path : paths) {
                                try {
                                    client.call(starter.nodeUrl + path, "GET", null, ANY);
                                } catch (NodeHttpClient.CallException e) {
                                    failures.add(starter.nodeUrl + path + " -> " + e.getStatusCode() + " " + e.getReason());
                                }
                            }
                            try {
                                starter.getSharedObjectMap();
                                starter.getItem(1, new String[]{"k1"});
                                starter.readSharedObject(map -> new HashSet<>(map.keySet()));
                            } catch (RuntimeException e) {
                                failures.add("copy failed: " + e);
                            }
                        }
                    }
                }));
            }
            readers.forEach(Thread::start);
            writers.forEach(Thread::start);
            for (var writer : writers)
                writer.join(60000);
            stop.set(true);
            for (var reader : readers)
                reader.join(10000);
            assertTrue(failures.isEmpty(), failures.size() + " failures, first: " + failures.peek());
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(10));

            // copies handed out are detached: changing them changes nothing shared
            long seq = cluster.service(1).copySharedObjectSeq().get(1);
            @SuppressWarnings("unchecked")
            var item = (Map<String, Object>) leader.getItem(1, new String[]{"k1"});
            item.put("v", -1);
            ((List<Object>) item.get("list")).add(99);
            assertEquals(Map.of("v", 1, "list", List.of(1)), leader.getItem(1, new String[]{"k1"}));
            leader.getSharedObject().clear();
            leader.getSharedObjectMap().get(2).clear();
            assertTrue(leader.getSharedObject().containsKey("k1"));
            assertTrue(leader.getSharedObjectMap().get(2).containsKey("f1"));
            assertEquals(seq, cluster.service(1).copySharedObjectSeq().get(1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));
        }
    }

    /** threads on node change data values of existing devices, as device scripts do, for duration: returns the changes made */
    private static long write(ClusterStarter node, int threads, Duration duration) throws InterruptedException {
        var stop = new AtomicBoolean(false);
        var writes = new AtomicLong();
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            writers.add(new Thread(() -> {
                var random = new Random();
                while (!stop.get()) {
                    node.mergeSharedObject(random.nextInt(1000000), "dev" + random.nextInt(10), "data", "k" + random.nextInt(200));
                    writes.incrementAndGet();
                }
            }));
        }
        writers.forEach(Thread::start);
        Thread.sleep(duration.toMillis());
        stop.set(true);
        for (var writer : writers)
            writer.join(10000);
        return writes.get();
    }

    @Test
    void steadyChangesReachFollowersOneByOneInsteadOfAsWholeObjects() throws Exception {
        var counting = new AtomicBoolean(false);
        var overwrites = new AtomicLong();
        try (var cluster = new TestCluster(3, 200, 2, 1, i -> new ClusterEvents()
                .overwritten("count", nodeIndex -> {
                    if (counting.get())
                        overwrites.incrementAndGet();
                }))) {
            cluster.startAll();
            var all = cluster.all();
            // objects the size of a driver's with a few devices, so that each whole copy costs something
            for (int d = 0; d < 10; d++) {
                var data = new HashMap<String, Object>();
                for (int k = 0; k < 200; k++)
                    data.put("k" + k, 0);
                for (int node : List.of(1, 2))
                    cluster.node(node).mergeSharedObject(Map.of("dev" + d, Map.of("id", "dev" + d, "data", data)));
            }
            cluster.awaitConsistent(all, Duration.ofSeconds(10));

            var seconds = Duration.ofSeconds(2);
            var results = new ArrayList<String>();
            long[][] phases = {{1, 1}, {1, 4}, {2, 4}};
            for (var phase : phases) {
                overwrites.set(0);
                counting.set(true);
                long writes = write(cluster.node((int) phase[0]), (int) phase[1], seconds);
                cluster.awaitConsistent(all, Duration.ofSeconds(10));
                counting.set(false);
                results.add(String.format("node %d, %d writer(s): %d changes (%d/s), %d overwritten", phase[0], phase[1], writes,
                        writes / seconds.toSeconds(), overwrites.get()));
                // about 1500-3000/s on loopback: a generous bound
                assertTrue(writes >= 300L * seconds.toSeconds(), "too slow: " + results);
                // before, the leader sent followers only the newest of the changes queued meanwhile: with more than one
                // writer, followers refused nearly every one and were overwritten with the whole object, once per change
                // (with larger objects, down to ~150 changes/s). A heartbeat still finds a follower behind now and then,
                // and fetches the whole object
                assertTrue(overwrites.get() <= 20 + writes / 50, "overwritten instead of changed: " + results);
            }
        }
    }

    /** threads on node change data values as {@link #write} does, until stop is set: each write's duration goes to latencies */
    private static List<Thread> timedWriters(ClusterStarter node, int threads, AtomicBoolean stop, List<Long> latencies) {
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            writers.add(new Thread(() -> {
                var random = new Random();
                List<Long> mine = new ArrayList<>();
                while (!stop.get()) {
                    long begin = System.nanoTime();
                    node.mergeSharedObject(random.nextInt(1000000), "dev" + random.nextInt(10), "data", "k" + random.nextInt(200));
                    mine.add((System.nanoTime() - begin) / 1_000_000);
                }
                latencies.addAll(mine);
            }));
        }
        return writers;
    }

    @Test
    void leaderWritesKeepGoingWhileEveryFollowerWrites() throws Exception {
        try (var cluster = new TestCluster(5, 200, 2, 1, null)) {
            cluster.startAll();
            var all = cluster.all();
            for (int node : all)
                cluster.node(node).mergeSharedObject(Map.of("dev0", Map.of("id", "dev0", "data", Map.of("k0", 0))));
            cluster.awaitConsistent(all, Duration.ofSeconds(10));

            var stop = new AtomicBoolean(false);
            var leaderLatencies = Collections.synchronizedList(new ArrayList<Long>());
            var followerLatencies = Collections.synchronizedList(new ArrayList<Long>());
            List<Thread> writers = new ArrayList<>(timedWriters(cluster.node(1), 2, stop, leaderLatencies));
            for (int node = 2; node <= 5; node++)
                writers.addAll(timedWriters(cluster.node(node), 2, stop, followerLatencies));
            writers.forEach(Thread::start);
            var seconds = Duration.ofSeconds(3);
            Thread.sleep(seconds.toMillis());
            stop.set(true);
            for (var writer : writers)
                writer.join(10000);
            cluster.awaitConsistent(all, Duration.ofSeconds(15));

            List<Long> leader = new ArrayList<>(leaderLatencies);
            Collections.sort(leader);
            long slow = leader.stream().filter(millis -> millis >= 900).count();
            String result = String.format("leader: %d writes (%d/s), p50=%d ms, p99=%d ms, max=%d ms, %d of them >= 900 ms; followers: %d writes",
                    leader.size(), leader.size() / seconds.toSeconds(), leader.get(leader.size() / 2), leader.get(leader.size() * 99 / 100),
                    leader.get(leader.size() - 1), slow, followerLatencies.size());
            // before, the followers' changes, one call each on a single propagation thread, starved the leader's own:
            // 6-8 writes/s, most of them waiting the probe timeout (1 s). Generous bounds, for a loaded machine
            assertTrue(leader.size() >= 100L * seconds.toSeconds(), "leader too slow: " + result);
            // no probe-timeout-length waits in the common case
            assertTrue(slow <= Math.max(2, leader.size() / 100), "leader writes waited the probe timeout: " + result);
            assertTrue(followerLatencies.size() >= 100L * seconds.toSeconds(), "followers too slow: " + result);
        }
    }

    @Test
    void sequenceKeepsGrowingAcrossARestart() throws Throwable {
        var deleted = new CopyOnWriteArrayList<Integer>();
        try (var cluster = new TestCluster(2, 200, 1, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (i == 1)
                        deleted.add(nodeIndex);
                }))) {
            long before = System.currentTimeMillis() * 1000;
            cluster.startAll();
            for (int i = 0; i < 3; i++)
                cluster.node(2).mergeSharedObject(Map.of("old" + i, i));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));
            long oldSeq = cluster.service(2).copySharedObjectSeq().get(2);
            assertTrue(oldSeq > before, "sequence " + oldSeq + " does not start from the clock");

            cluster.kill(2);
            TestCluster.await(Duration.ofSeconds(10), () -> deleted.contains(2), () -> "node 2 not removed");
            cluster.restart(2);
            long newSeq = cluster.service(2).copySharedObjectSeq().get(2);
            assertTrue(newSeq > oldSeq, "sequence went back from " + oldSeq + " to " + newSeq);

            cluster.node(2).mergeSharedObject(Map.of("new", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(10));
            assertEquals(Map.of("new", 1), cluster.node(1).getSharedObjectMap().get(2));
        }
    }
}
