package com.sds.communicators.cluster;

import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Removal of lost nodes: durable failover, split-brain heal and entries of nodes that are gone. */
class ClusterFailoverTest {
    private static final int HEARTBEAT_MILLIS = 200;
    private static final int LOST_SECONDS = 1;

    private static void awaitNoReplica(TestCluster cluster, Collection<Integer> holders, int nodeIndex, Duration timeout) {
        TestCluster.await(timeout, () -> holders.stream().noneMatch(i -> cluster.replicas(i).contains(nodeIndex)),
                () -> "replica of node " + nodeIndex + " still held:" + cluster.describe(holders));
    }

    @Test
    void newLeaderRerunsAFailoverThePreviousLeaderDidNotFinish() throws Exception {
        var leaderInFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failovers = new ConcurrentHashMap<Integer, Map<String, Object>>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("failover", (nodeIndex, object) -> {
                    if (nodeIndex != 3)
                        return;
                    if (i == 1) {
                        // the leader dies in the middle of the failover
                        leaderInFailover.countDown();
                        release.await();
                        return;
                    }
                    failovers.put(i, object);
                }))) {
            cluster.startAll();
            var device = Map.<String, Object>of("dev3", Map.of("id", "dev3"));
            cluster.node(3).mergeSharedObject(device);
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.kill(3);
            assertTrue(leaderInFailover.await(LOST_SECONDS + 5, TimeUnit.SECONDS));
            // the followers keep node 3's object while the failover runs
            Thread.sleep(3L * HEARTBEAT_MILLIS);
            for (int i : List.of(2, 4))
                assertTrue(cluster.replicas(i).contains(3), "node " + i + " dropped node 3 before the failover finished");

            cluster.kill(1);
            TestCluster.await(Duration.ofSeconds(15), () -> failovers.containsKey(2), () -> "no failover of node 3 on the new leader:" +
                    cluster.describe(List.of(2, 4)));
            assertEquals(Position.LEADER, cluster.node(2).getPosition());
            assertEquals(device, failovers.get(2));
            awaitNoReplica(cluster, List.of(2, 4), 3, Duration.ofSeconds(5));
            assertFalse(failovers.containsKey(4));
        } finally {
            release.countDown();
        }
    }

    @Test
    void restartedNodeElectedRightAfterStartStillFailsOverTheDeadLeader() throws Throwable {
        var failovers = new ConcurrentHashMap<String, Map<String, Object>>();
        try (var cluster = new TestCluster(3, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> failovers.put(i + ":" + nodeIndex, object)))) {
            cluster.startAll();
            var device = Map.<String, Object>of("dev2", Map.of("id", "dev2"));
            cluster.node(2).mergeSharedObject(device);
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.kill(1);
            TestCluster.await(Duration.ofSeconds(10), () -> failovers.containsKey("2:1"), () -> "node 1 not removed:" +
                    cluster.describe(List.of(2, 3)));
            failovers.clear();
            // node 1 is the lowest index, so it is elected when the leader dies right after node 1 prepared
            cluster.restart(1);
            cluster.kill(2);

            var live = List.of(1, 3);
            TestCluster.await(Duration.ofSeconds(15), () -> failovers.entrySet().stream()
                            .anyMatch(entry -> entry.getKey().endsWith(":2") && device.equals(entry.getValue())),
                    () -> "no failover of node 2 with its object: " + failovers + cluster.describe(live));
            awaitNoReplica(cluster, live, 2, Duration.ofSeconds(5));
        }
    }

    @Test
    void splitBrainHealAdoptsTheRealObjectOfANodeThatDiedBeforeTheHeal() throws Exception {
        var healed = new AtomicBoolean(false);
        var deletedAfterHeal = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (nodeIndex == 4 && healed.get())
                        deletedAfterHeal.add(i + ":" + TestCluster.json(object));
                }))) {
            cluster.startAll();
            cluster.node(4).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            // node 4 registers something on its side only, then dies before the partition heals
            cluster.node(4).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                var replica = cluster.service(3).copyReplica(4, false);
                return replica != null && replica.obj.containsKey("during");
            }, () -> "change not on node 3");
            cluster.kill(4);
            healed.set(true);
            cluster.heal();

            var live = List.of(1, 2, 3);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (deletedAfterHeal.isEmpty() || live.stream().anyMatch(i -> cluster.replicas(i).contains(4))) {
                for (int i : live) {
                    var replica = cluster.service(i).copySharedObject(true).sharedObject.get(4);
                    // before, the winner created an empty placeholder and kept it instead of the real object
                    if (replica != null)
                        assertTrue(replica.containsKey("during"), "node " + i + " holds " + replica + " for node 4");
                }
                assertTrue(System.nanoTime() < deadline, "node 4 not removed after the heal: " + deletedAfterHeal + cluster.describe(live));
                Thread.sleep(20);
            }
            for (var event : deletedAfterHeal)
                assertTrue(event.contains("\"during\""), "failover without the real object: " + deletedAfterHeal);
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 1, () -> "no single leader:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    @Test
    void demotedLeaderKeepsWhatOnlyItHoldsUntilTheWinnerHasTakenItOver() throws Exception {
        var healing = new AtomicBoolean(false);
        var failovers = new CopyOnWriteArrayList<String>();
        // lost after 2 s: node 4's removal on side B is still pending while the partition heals one way only
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, 2, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (nodeIndex == 4 && healing.get())
                        failovers.add(i + ":" + TestCluster.json(object));
                }))) {
            cluster.startAll();
            cluster.node(4).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(15), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            cluster.node(4).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                var replica = cluster.service(3).copyReplica(4, false);
                return replica != null && replica.obj.containsKey("during");
            }, () -> "change not on node 3");
            cluster.kill(4);
            healing.set(true);

            // node 1's heartbeats reach node 3 again, which steps down, but its call handing its objects over fails
            cluster.healFrom(Set.of(1, 2));
            TestCluster.await(Duration.ofSeconds(5), () -> cluster.node(3).getPosition() == Position.FOLLOWER,
                    () -> "node 3 still leads:" + cluster.describe(List.of(1, 2, 3)));
            long until = System.nanoTime() + Duration.ofMillis(4L * HEARTBEAT_MILLIS).toNanos();
            while (System.nanoTime() < until) {
                // before, node 3 dropped it right away, as the leader holds none, and node 4 was never failed over
                var replica = cluster.service(3).copyReplica(4, false);
                assertTrue(replica != null && replica.obj.containsKey("during"), "node 3 holds " + replica + " for node 4:" +
                        cluster.describe(List.of(1, 2, 3)));
                Thread.sleep(20);
            }

            cluster.heal();
            var live = List.of(1, 2, 3);
            TestCluster.await(Duration.ofSeconds(15), () -> !failovers.isEmpty() && live.stream().noneMatch(i -> cluster.replicas(i).contains(4)),
                    () -> "node 4 not failed over after the heal: " + failovers + cluster.describe(live));
            for (var event : failovers)
                assertTrue(event.contains("\"during\""), "failover without the object of node 4: " + failovers);
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 1, () -> "no single leader:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    @Test
    void objectOfASplitBrainLeaderThatDiedRightBeforeTheHealIsFailedOverByTheWinner() throws Exception {
        var healed = new AtomicBoolean(false);
        var failovers = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (nodeIndex == 3 && healed.get())
                        failovers.add(i + ":" + TestCluster.json(object));
                }))) {
            cluster.startAll();
            cluster.node(3).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4))
                            && cluster.service(1).copyReplica(3, true) == null,
                    () -> "no split brain, or node 3 not failed over on side A:" + cluster.describe(cluster.all()));
            // node 3 leads side B, registers something there, and dies right before the heal: only node 4, which still
            // follows it, holds that object
            cluster.node(3).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                var replica = cluster.service(4).copyReplica(3, false);
                return replica != null && replica.obj.containsKey("during");
            }, () -> "change not on node 4");
            cluster.kill(3);
            healed.set(true);
            cluster.heal();

            // before, node 4 dropped it at node 1's first heartbeat, since node 1 holds none: node 3's devices of the
            // split brain were never failed over. Now node 4 offers it to the leader it follows from then on
            var live = List.of(1, 2, 4);
            TestCluster.await(Duration.ofSeconds(15), () -> !failovers.isEmpty() && live.stream().noneMatch(i -> cluster.replicas(i).contains(3)),
                    () -> "node 3 not failed over after the heal: " + failovers + cluster.describe(live));
            for (var event : failovers)
                assertTrue(event.startsWith("1:") && event.contains("\"during\""), "failover without the object of node 3: " + failovers);
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 1, () -> "no single leader:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    @Test
    void objectWhoseFailoverRunsOnTheDemotedLeaderAtTheHealIsFailedOverByTheWinnerAndLeftNowhere() throws Exception {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var healed = new AtomicBoolean(false);
        var failovers = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (nodeIndex != 4)
                        return;
                    boolean afterHeal = healed.get();
                    failovers.add(i + (afterHeal ? " after" : " before") + " the heal:" + TestCluster.json(object));
                    // node 3's failover on side B takes a while (the driver lets the membership settle first): it still
                    // runs when the partition heals
                    if (i == 3 && !afterHeal) {
                        inFailover.countDown();
                        release.await();
                    }
                }))) {
            cluster.startAll();
            cluster.node(4).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            cluster.node(4).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                var replica = cluster.service(3).copyReplica(4, false);
                return replica != null && replica.obj.containsKey("during");
            }, () -> "change not on node 3");
            cluster.kill(4);
            assertTrue(inFailover.await(LOST_SECONDS + 5, TimeUnit.SECONDS), "node 3 did not fail node 4 over");
            healed.set(true);
            cluster.heal();

            // node 3 is demoted and hands node 4's object over; it follows node 1 then, and holds that object again when
            // its own failover finishes, so it does not broadcast it
            var live = List.of(1, 2, 3);
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 1, () -> "no single leader:" + cluster.describe(live));
            Thread.sleep(3L * HEARTBEAT_MILLIS);
            release.countDown();
            // before, node 1 did not fail it over while node 3 was a member, waiting for that broadcast: node 4's object
            // stayed on every node for good
            TestCluster.await(Duration.ofSeconds(15), () -> failovers.stream().anyMatch(failover -> failover.startsWith("1 after"))
                            && live.stream().noneMatch(i -> cluster.service(i).copyReplica(4, true) != null),
                    () -> "node 4 not failed over by the winner, or still held: " + failovers + cluster.describe(live));
            for (var failover : failovers)
                assertTrue(failover.contains("\"during\"") || failover.contains(" before the heal:"), "failover without the object of node 4: " + failovers);
            Thread.sleep(2000);
            assertTrue(live.stream().noneMatch(i -> cluster.replicas(i).contains(4)), "node 4 held again:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        } finally {
            release.countDown();
        }
    }

    @Test
    void objectWhoseFailoverFinishesOnTheDemotedLeaderDuringAOneWayHealIsFailedOverOnce() throws Exception {
        var inFailover = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failovers = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(5, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    // node 5's object of the split brain
                    if (nodeIndex != 5 || !object.containsKey("during"))
                        return;
                    failovers.add(i + ":" + TestCluster.json(object));
                    // node 3's failover on side B still runs when side A's heartbeats reach side B
                    if (i == 3) {
                        inFailover.countDown();
                        release.await();
                    }
                }))) {
            cluster.startAll();
            cluster.node(5).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4, 5));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4, 5)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            cluster.node(5).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> List.of(3, 4).stream().allMatch(i -> {
                var replica = cluster.service(i).copyReplica(5, false);
                return replica != null && replica.obj.containsKey("during");
            }), () -> "change not on nodes 3 and 4");
            cluster.kill(5);
            assertTrue(inFailover.await(LOST_SECONDS + 5, TimeUnit.SECONDS), "node 3 did not fail node 5 over");

            // side A's heartbeats reach side B, but not the other way round: node 3 is demoted, and neither its handover
            // nor node 4's offer (node 5's object, which node 1 lacks) gets through to node 1
            cluster.healFrom(Set.of(1, 2));
            TestCluster.await(Duration.ofSeconds(5), () -> cluster.node(3).getPosition() == Position.FOLLOWER,
                    () -> "node 3 not demoted:" + cluster.describe(List.of(1, 2, 3, 4)));
            Thread.sleep(3L * HEARTBEAT_MILLIS);
            // node 3's failover finishes after its demotion. Before, it was not broadcast: node 4 kept node 5's object, and
            // once the heal was complete, node 1 took it over from node 4's offer (or from node 3's handover, which still
            // carried it) and failed it over a second time. Now the broadcast, marked as such, makes node 4 drop it (node 3
            // listed it there). Node 3 does not hold it again: node 1 did not take it over from its handover, which did not
            // get through
            release.countDown();
            TestCluster.await(Duration.ofSeconds(5), () -> cluster.service(4).copyReplica(5, true) == null,
                    () -> "node 4 still holds node 5's object:" + cluster.describe(List.of(3, 4)));

            cluster.heal();
            var live = List.of(1, 2, 3, 4);
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 1, () -> "no single leader:" + cluster.describe(live));
            awaitNoReplica(cluster, live, 5, Duration.ofSeconds(10));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
            // longer than the absence grace, after which node 1 would fail over a copy it had taken over
            Thread.sleep(LOST_SECONDS * 1000L + 5L * HEARTBEAT_MILLIS);
            assertEquals(List.of("3:{\"before\":1,\"during\":1}"), failovers);
            assertTrue(live.stream().noneMatch(i -> cluster.replicas(i).contains(5)), "node 5 held again:" + cluster.describe(live));
        } finally {
            release.countDown();
        }
    }

    @Test
    void objectOfAWinnerThatDiesBeforeItsOwnFailoverIsFailedOverByTheNextLeaderAfterTheDemotedLeadersFailoverPlacedNothing() throws Exception {
        var inFailover = new CountDownLatch(1);
        var refused = new CountDownLatch(1);
        var failovers = new CopyOnWriteArrayList<String>();
        var nodes = new AtomicReference<TestCluster>();
        // lost after 2 s: the winner's own failover of the object it takes over, after the absence grace, would come only
        // well after it dies
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, 2, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    // node 4's object of the split brain
                    if (nodeIndex != 4 || !object.containsKey("during"))
                        return;
                    if (i != 3 || refused.getCount() == 0) {
                        failovers.add(i + ":" + TestCluster.json(object));
                        return;
                    }
                    // node 3's failover, begun on side B, connects the devices through the leader, which after the heal is
                    // node 1: it refuses them while it holds node 4's object, taken over from node 3. This failover
                    // places nothing
                    inFailover.countDown();
                    var test = nodes.get();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (test.node(3).getPosition() != Position.FOLLOWER && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    // node 1's heartbeats no longer reach node 3, which does not get node 4's object back from node 1: rounds
                    // 6 and 7 broadcast its failover (marked as finished after its leader was demoted)
                    test.cut(1, 3);
                    while (!(holdsDuring(test, 1) && holdsDuring(test, 2)) && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    // node 1 is about to die: nothing it sends reaches node 2 any more
                    test.cut(1, 2);
                    refused.countDown();
                }))) {
            nodes.set(cluster);
            cluster.startAll();
            cluster.node(4).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(15), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            cluster.node(4).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                var replica = cluster.service(3).copyReplica(4, false);
                return replica != null && replica.obj.containsKey("during");
            }, () -> "change not on node 3");
            cluster.kill(4);
            assertTrue(inFailover.await(10, TimeUnit.SECONDS), "node 3 did not fail node 4 over");
            cluster.heal();
            assertTrue(refused.await(15, TimeUnit.SECONDS), "node 1 did not take node 4's object over:" + cluster.describe(List.of(1, 2, 3)));

            // node 1 keeps its copy, to fail it over after the grace, but dies before. Round 6 made node 2 drop its copy on
            // that broadcast, and remember the object as failed over: node 2, which leads next, held none and would have
            // declined an offer of it, and node 3 offered none (it remembered its own failover as done), so node 4's
            // devices ran nowhere. Round 7 made node 2 keep its copy, which came from node 1 (not listed here by node 3).
            // Now node 3, whose handover node 1 took over, holds its copy again and does not broadcast that failover:
            // node 2 keeps its copy, and fails it over as leader
            Thread.sleep(300);
            var holders = List.of(1, 2, 3).stream().filter(i -> holdsDuring(cluster, i)).toList();
            cluster.kill(1);
            var live = List.of(2, 3);
            TestCluster.await(Duration.ofSeconds(15), () -> !failovers.isEmpty() && live.stream().noneMatch(i -> cluster.service(i).copyReplica(4, true) != null),
                    () -> "node 4 not failed over after node 1 died (holders of its object before: " + holders + "): " + failovers + cluster.describe(live));
            Thread.sleep(2000);
            assertEquals(List.of("2:{\"before\":1,\"during\":1}"), failovers);
            assertTrue(live.stream().noneMatch(i -> cluster.replicas(i).contains(4)), "node 4 held again:" + cluster.describe(live));
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 2, () -> "no single leader:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    @Test
    void objectOfAWinnerThatDiesRightAfterTheDemotedLeadersFailoverIsFailedOverWhenItsFollowerFollowedTheDemotedLeaderAtTheHeal() throws Exception {
        // node 2, the follower of node 1, the winner, leads after it
        failoverOfTheObjectOfAWinnerThatDiesRightAfterTheDemotedLeadersFailover(3, 2);
    }

    @Test
    void objectOfAWinnerThatDiesRightAfterTheDemotedLeadersFailoverIsFailedOverByTheDemotedLeaderLeadingAfterIt() throws Exception {
        // node 2, the demoted leader, leads after node 1, the winner
        failoverOfTheObjectOfAWinnerThatDiesRightAfterTheDemotedLeadersFailover(2, 3);
    }

    /**
     * A double fault at a heal. Node 4 dies on side B of a split brain, whose leader (demoted) fails it over. The heal
     * reaches follower, the winner's follower, before the winner's heartbeats do: it follows demoted for a moment, and
     * fetches node 4's object from it. Then node 1's heartbeat demotes demoted, which hands that object over; its failover,
     * which connects the devices through node 1, places nothing while node 1 holds that object, and finishes. Node 1 dies
     * right after, before its own failover of node 4 (after the absence grace). Round 7 broadcast demoted's failover as
     * finished after its demotion: follower dropped its copy, which demoted had listed there, and demoted held none, so
     * no node held node 4's object any more, and its devices ran nowhere
     */
    private static void failoverOfTheObjectOfAWinnerThatDiesRightAfterTheDemotedLeadersFailover(int demoted, int follower) throws Exception {
        var inFailover = new CountDownLatch(1);
        var refused = new CountDownLatch(1);
        var failovers = new CopyOnWriteArrayList<String>();
        var nodes = new AtomicReference<TestCluster>();
        // lost after 2 s: node 1's own failover of the object it takes over, after the absence grace, would come only well
        // after it dies
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, 2, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    // node 4's object of the split brain
                    if (nodeIndex != 4 || !object.containsKey("during"))
                        return;
                    if (i != demoted || refused.getCount() == 0) {
                        failovers.add(i + ":" + TestCluster.json(object));
                        return;
                    }
                    // demoted's failover, begun on side B, connects the devices through the leader, which after the heal
                    // is node 1: it refuses them while it holds node 4's object, taken over from demoted. This failover
                    // places nothing
                    inFailover.countDown();
                    var test = nodes.get();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (test.node(demoted).getPosition() != Position.FOLLOWER && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    // from its demoting heartbeat on, nothing of node 1's reaches demoted: neither its heartbeats nor
                    // node 4's object pushed back
                    test.cut(1, demoted);
                    while (!holdsDuring(test, 1) && System.nanoTime() < deadline)
                        Thread.sleep(1);
                    refused.countDown();
                }))) {
            nodes.set(cluster);
            cluster.startAll();
            cluster.node(4).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(demoted, 4));
            TestCluster.await(Duration.ofSeconds(15), () -> cluster.node(demoted).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, follower)) && cluster.node(demoted).getCluster().equals(Set.of(demoted, 4)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            cluster.node(4).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> holdsDuring(cluster, demoted), () -> "change not on node " + demoted);
            cluster.kill(4);
            assertTrue(inFailover.await(10, TimeUnit.SECONDS), "node " + demoted + " did not fail node 4 over");

            // the heal, but for node 1's links to follower and demoted: follower hears only demoted's heartbeats, follows it,
            // and fetches node 4's object from it, as listed there by demoted
            var live = List.of(2, 3);
            cluster.cut(1, follower);
            cluster.healFrom(Set.of(demoted));
            cluster.healFrom(Set.of(follower));
            TestCluster.await(Duration.ofSeconds(5), () -> holdsDuring(cluster, follower),
                    () -> "node " + follower + " did not fetch node 4's object from node " + demoted + ":" + cluster.describe(live));
            Thread.sleep(3L * HEARTBEAT_MILLIS);
            // node 1's heartbeats reach demoted, which steps down
            cluster.restore(1, demoted);
            assertTrue(refused.await(15, TimeUnit.SECONDS), "node 1 did not take node 4's object over:" + cluster.describe(List.of(1, 2, 3)));

            // node 1 keeps what it holds, to fail it over after the grace, but dies before
            Thread.sleep(300);
            var holders = List.of(1, 2, 3).stream().filter(i -> holdsDuring(cluster, i)).toList();
            cluster.kill(1);
            TestCluster.await(Duration.ofSeconds(15), () -> !failovers.isEmpty() && live.stream().noneMatch(i -> cluster.service(i).copyReplica(4, true) != null),
                    () -> "node 4 not failed over after node 1 died (holders of its object before: " + holders + "): " + failovers + cluster.describe(live));
            Thread.sleep(2000);
            assertEquals(List.of("2:{\"before\":1,\"during\":1}"), failovers, "holders of node 4's object before node 1 died: " + holders);
            assertTrue(live.stream().noneMatch(i -> cluster.replicas(i).contains(4)), "node 4 held again:" + cluster.describe(live));
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 2, () -> "no single leader:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    private static boolean holdsDuring(TestCluster cluster, int holder) {
        var replica = cluster.service(holder).copyReplica(4, false);
        return replica != null && replica.obj.containsKey("during");
    }

    @Test
    void restartedLeaderIsOfferedTheObjectOfANodeThatDiedMeanwhile() throws Throwable {
        var failovers = new ConcurrentHashMap<String, Map<String, Object>>();
        try (var cluster = new TestCluster(3, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> failovers.put(i + ":" + nodeIndex, object)))) {
            cluster.startAll();
            var device = Map.<String, Object>of("dev3", Map.of("id", "dev3"));
            cluster.node(3).mergeSharedObject(device);
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            // node 2 elects nobody for a while: as far as its timers can tell, its JVM stalls
            cluster.service(2).simulateStall(3000);
            Thread.sleep(1200);
            // meanwhile node 3 dies and the leader restarts. Having heard no leader while it prepared, node 1 leads again,
            // with nothing but its own object: only node 2 still holds node 3's
            cluster.kill(3);
            cluster.restart(1);
            assertEquals(Position.LEADER, cluster.node(1).getPosition(), "restarted node 1 does not lead:" + cluster.describe(List.of(1, 2)));

            // before, node 2 dropped it at the restarted leader's first heartbeat, and node 3 was never failed over
            var live = List.of(1, 2);
            TestCluster.await(Duration.ofSeconds(15), () -> device.equals(failovers.get("1:3")),
                    () -> "node 3 not failed over by the restarted leader: " + failovers + cluster.describe(live));
            awaitNoReplica(cluster, live, 3, Duration.ofSeconds(5));
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(live) == 1, () -> "no single leader:" + cluster.describe(live));
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    @Test
    void splitBrainIsResolvedOnceWhileAPeerHangs() throws Exception {
        var healed = new AtomicBoolean(false);
        var resolved = new CopyOnWriteArrayList<Integer>();
        var failovers = new CopyOnWriteArrayList<String>();
        try (var hung = new StubServer().hang("/hung");
             var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                     .splitBrainResolved("record", () -> resolved.add(i))
                     .clusterDeleted("record", (nodeIndex, object) -> {
                         if (nodeIndex == 4 && healed.get())
                             failovers.add(i + ":" + TestCluster.json(object));
                     }))) {
            cluster.startAll();
            cluster.node(4).mergeSharedObject(Map.of("before", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4)),
                    () -> "no split brain:" + cluster.describe(cluster.all()));
            cluster.node(4).mergeSharedObject(Map.of("during", 1));
            TestCluster.await(Duration.ofSeconds(5), () -> {
                var replica = cluster.service(3).copyReplica(4, false);
                return replica != null && replica.obj.containsKey("during");
            }, () -> "change not on node 3");
            cluster.kill(4);
            // a peer that accepts connections and never answers, known to every node from the heal on
            for (int i : List.of(1, 2, 3))
                cluster.node(i).nodeTargetUrls.add(hung.url("/hung"));
            healed.set(true);
            cluster.heal();

            var live = List.of(1, 2, 3);
            TestCluster.await(Duration.ofSeconds(15), () -> !failovers.isEmpty() && live.stream().noneMatch(i -> cluster.replicas(i).contains(4)),
                    () -> "node 4 not failed over after the heal: " + failovers + cluster.describe(live));
            for (var event : failovers)
                assertTrue(event.contains("\"during\""), "failover without the object of node 4: " + failovers);
            // before, the winner checked the hung peer (read timeout) before answering: the demoted node's call timed out,
            // and each of its retries resolved the split brain again
            Thread.sleep(3000);
            assertEquals(List.of(1), resolved);
        }
    }

    @Test
    void aNodeHearingBothSplitBrainLeadersMakesNeitherFailTheOtherSidesLiveNodesOver() throws Exception {
        var bridged = new AtomicBoolean(false);
        var failovers = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    // every node lives on: from the bridge on, none may be failed over
                    if (bridged.get())
                        failovers.add(i + "->" + nodeIndex);
                }))) {
            cluster.startAll();
            for (int i : cluster.all())
                cluster.node(i).mergeSharedObject(Map.of("d" + i + "a", 1));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            cluster.partition(Set.of(3, 4));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                            && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4))
                            && cluster.replicas(1).equals(Set.of(1, 2)) && cluster.replicas(3).equals(Set.of(3, 4)),
                    () -> "no split brain, or its failovers not finished:" + cluster.describe(cluster.all()));
            // newer than the objects each side failed over
            for (int i : cluster.all())
                cluster.node(i).mergeSharedObject(Map.of("d" + i + "b", 1));
            Thread.sleep(3L * HEARTBEAT_MILLIS);

            // nodes 2 and 3 hear each other again: node 2 hears both leaders, and each leader still does not hear the
            // other side's nodes
            bridged.set(true);
            cluster.restore(2, 3);
            Thread.sleep(3000);
            // before, node 2 offered each leader the other side's objects as those of nodes that are gone, whenever it
            // switched between the leaders, and both leaders failed live nodes over
            assertEquals(List.of(), failovers, "live nodes failed over while bridged:" + cluster.describe(cluster.all()));

            cluster.heal();
            TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(cluster.all()) == 1, () -> "no single leader:" + cluster.describe(cluster.all()));
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(10));
            Thread.sleep(1500);
            assertEquals(List.of(), failovers, "live nodes failed over after the heal:" + cluster.describe(cluster.all()));
        }
    }

    @Test
    void splitBrainIsResolvedExactlyOncePerHeal() throws Exception {
        var resolved = new CopyOnWriteArrayList<Integer>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .splitBrainResolved("record", () -> resolved.add(i)))) {
            cluster.startAll();
            var all = cluster.all();
            for (int heal = 1; heal <= 2; heal++) {
                cluster.partition(Set.of(3, 4));
                TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 10), () -> cluster.node(3).getPosition() == Position.LEADER
                                && cluster.node(1).getCluster().equals(Set.of(1, 2)) && cluster.node(3).getCluster().equals(Set.of(3, 4))
                                && cluster.replicas(1).equals(Set.of(1, 2)) && cluster.replicas(3).equals(Set.of(3, 4)),
                        () -> "no split brain:" + cluster.describe(all));
                resolved.clear();
                cluster.heal();
                TestCluster.await(Duration.ofSeconds(10), () -> cluster.leaderOf(all) == 1, () -> "no single leader:" + cluster.describe(all));
                Thread.sleep(3000);
                // before, the heartbeat that demoted node 3 often listed everything already (the winner pulls the objects
                // of the nodes it hears again), which cleared the handover before it was sent: no event, no fan-out
                assertEquals(List.of(1), resolved, "heal " + heal + ":" + cluster.describe(all));
                cluster.awaitConsistent(all, Duration.ofSeconds(10));
            }
        }
    }

    @Test
    void restartedLeaderFailsOverOnceANodeThatDiesWhileItPrepares() throws Throwable {
        var restarting = new AtomicBoolean(false);
        var heard = new CountDownLatch(1);
        var failovers = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterAdded("record", nodeIndex -> {
                    if (i == 1 && nodeIndex == 4 && restarting.get())
                        heard.countDown();
                })
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (restarting.get())
                        failovers.add(i + ":" + nodeIndex + ":" + TestCluster.json(object));
                }))) {
            cluster.startAll();
            var device = Map.<String, Object>of("dev4", Map.of("id", "dev4"));
            cluster.node(4).mergeSharedObject(device);
            cluster.awaitConsistent(cluster.all(), Duration.ofSeconds(5));

            // nodes 2 and 3 elect nobody for a while: as far as their timers can tell, their JVMs stall
            cluster.service(2).simulateStall(3000);
            cluster.service(3).simulateStall(3000);
            Thread.sleep(1200);
            // the leader restarts, and node 4 dies while node 1 prepares, after node 1 heard it: node 1 leads again with
            // nothing but its own object, while node 4 is still its member for a while
            restarting.set(true);
            var restart = new Thread(() -> {
                try {
                    cluster.restart(1);
                } catch (Throwable e) {
                    throw new IllegalStateException(e);
                }
            });
            restart.start();
            assertTrue(heard.await(5, TimeUnit.SECONDS), "restarted node 1 did not hear node 4");
            // half way through the preparation: node 4 is still node 1's member for a while after node 1 leads
            Thread.sleep(LOST_SECONDS * 500L);
            cluster.kill(4);
            restart.join(10000);
            var live = List.of(1, 2, 3);
            assertEquals(Position.LEADER, cluster.node(1).getPosition(), "restarted node 1 does not lead:" + cluster.describe(live));

            // before, the followers offered node 4's object while it was still a member of node 1, which declined it,
            // counted the offer delivered, and dropped their copies: node 4 was never failed over
            String expected = "1:4:" + TestCluster.json(device);
            TestCluster.await(Duration.ofSeconds(15), () -> failovers.contains(expected),
                    () -> "node 4 not failed over by the restarted leader: " + failovers + cluster.describe(live));
            awaitNoReplica(cluster, live, 4, Duration.ofSeconds(5));
            Thread.sleep(2000);
            assertEquals(List.of(expected), failovers.stream().filter(failover -> failover.contains(":4:")).toList());
            cluster.awaitConsistent(live, Duration.ofSeconds(10));
        }
    }

    @Test
    void leaderIgnoresAClusterDeletedBroadcast() throws Exception {
        try (var cluster = new TestCluster(3, HEARTBEAT_MILLIS, LOST_SECONDS, 1, null)) {
            cluster.startAll();
            var all = cluster.all();
            cluster.node(2).mergeSharedObject(Map.of("dev2", 1));
            cluster.awaitConsistent(all, Duration.ofSeconds(5));

            // a demoted leader's late broadcast for node 2, which is alive: data only, and not for the leader
            var leader = cluster.node(1);
            leader.getNodeHttpClient().call(leader.nodeUrl + "/cluster/internal/cluster-deleted/2", "DELETE", null, null);
            assertEquals(Map.of("dev2", 1), leader.getSharedObjectMap().get(2));
            Thread.sleep(5L * HEARTBEAT_MILLIS);
            assertEquals(Map.of("dev2", 1), leader.getSharedObjectMap().get(2));
            // a follower drops its copy, and fetches it again from the leader (the sequence failed over, sent with the
            // broadcast, is only remembered)
            var follower = cluster.node(3);
            long seq = cluster.service(3).copySharedObjectSeq().get(2);
            follower.getNodeHttpClient().call(follower.nodeUrl + "/cluster/internal/cluster-deleted/2?seq=" + seq, "DELETE", null, null);
            cluster.awaitConsistent(all, Duration.ofSeconds(5));
            assertEquals(Set.of(1, 2, 3), cluster.replicas(3));
        }
    }

    @Test
    void entriesOfDeadOrUnknownNodesDisappearEverywhere() throws Exception {
        var deletedOnLeader = new ConcurrentHashMap<Integer, Map<String, Object>>();
        try (var cluster = new TestCluster(3, HEARTBEAT_MILLIS, LOST_SECONDS, 1, i -> new ClusterEvents()
                .clusterDeleted("record", (nodeIndex, object) -> {
                    if (i == 1)
                        deletedOnLeader.put(nodeIndex, object);
                }))) {
            cluster.startAll();
            var all = cluster.all();

            // a follower's entry for a node the leader does not hold goes with the next leader heartbeat
            cluster.service(2).overwriteSharedObject(9, new ClusterService.MergeSharedObjectInfo(5, new HashMap<>(Map.of("ghost", 9))));
            awaitNoReplica(cluster, List.of(2), 9, Duration.ofSeconds(3));

            // an entry the leader holds for a node that is no member goes like a lost node, failover included
            cluster.service(1).overwriteSharedObject(8, new ClusterService.MergeSharedObjectInfo(5, new HashMap<>(Map.of("ghost", 8))));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 5), () -> deletedOnLeader.containsKey(8), () -> "entry of node 8 not removed");
            assertEquals(Map.of("ghost", 8), deletedOnLeader.get(8));
            awaitNoReplica(cluster, all, 8, Duration.ofSeconds(5));

            // a dead node's entry goes everywhere, and a late change it sent does not bring it back
            cluster.node(3).mergeSharedObject(Map.of("dev3", 1));
            cluster.awaitConsistent(all, Duration.ofSeconds(5));
            long seq = cluster.service(3).copySharedObjectSeq().get(3);
            cluster.kill(3);
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 5), () -> deletedOnLeader.containsKey(3), () -> "node 3 not removed");
            assertEquals(Map.of("dev3", 1), deletedOnLeader.get(3));
            awaitNoReplica(cluster, List.of(1, 2), 3, Duration.ofSeconds(5));

            var leader = cluster.node(1);
            var late = assertThrows(NodeHttpClient.CallException.class, () -> leader.getNodeHttpClient().call(
                    leader.nodeUrl + "/cluster/internal/merge-shared-object-to-leader/3", "POST",
                    new ClusterService.MergeSharedObjectInfo(seq + 1, Map.of("late", 1)), null));
            assertEquals(503, late.getStatusCode());
            Thread.sleep(5L * HEARTBEAT_MILLIS);
            for (int i : List.of(1, 2))
                assertFalse(cluster.replicas(i).contains(3), "node " + i + " holds node 3 again");
        }
    }
}
