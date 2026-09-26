package com.sds.communicators.cluster;

import com.sds.communicators.common.type.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Membership changes after partitions, one-way link loss and stalls, on nodes whose links can be cut. */
class ClusterMembershipTest {
    private static final int HEARTBEAT_MILLIS = 200;
    private static final int LOST_SECONDS = 1;

    private static void writeSome(TestCluster cluster) {
        for (int i : cluster.all()) {
            var node = cluster.node(i);
            node.mergeSharedObject(Map.of("a" + i, 1));
            node.mergeSharedObject(Map.of("b" + i, Map.of("x", 1)));
            node.mergeSharedObject(Map.of("c" + i, "v"));
            node.deleteSharedObject("a" + i);
        }
    }

    private static void awaitCluster(TestCluster cluster, Collection<Integer> nodes, Set<Integer> expected, Duration timeout) {
        TestCluster.await(timeout, () -> nodes.stream().allMatch(i -> cluster.node(i).getCluster().equals(expected)),
                () -> "membership is not " + expected + ":" + cluster.describe(nodes));
    }

    private static void rejoinWithoutRestart(int victim) throws Exception {
        try (var cluster = new TestCluster(3, HEARTBEAT_MILLIS, LOST_SECONDS, 0, null)) {
            cluster.startAll();
            var all = cluster.all();
            writeSome(cluster);
            cluster.awaitConsistent(all, Duration.ofSeconds(5));

            var others = new ArrayList<>(all);
            others.remove((Integer) victim);
            cluster.partition(Set.of(victim));
            // until both sides have declared the other lost; the victim changes nothing meanwhile
            awaitCluster(cluster, others, new HashSet<>(others), Duration.ofSeconds(LOST_SECONDS + 5));
            awaitCluster(cluster, List.of(victim), Set.of(victim), Duration.ofSeconds(LOST_SECONDS + 5));
            Thread.sleep(4L * HEARTBEAT_MILLIS);

            cluster.heal();
            awaitCluster(cluster, all, Set.copyOf(all), Duration.ofSeconds(10));
            // before, followers kept the victim's sequence without its object and never fetched it again
            cluster.awaitConsistent(all, Duration.ofSeconds(10));

            cluster.node(victim).mergeSharedObject(Map.of("after", Map.of("k", 1)));
            cluster.awaitConsistent(all, Duration.ofSeconds(5));
            Thread.sleep(10L * HEARTBEAT_MILLIS);
            assertEquals(List.of(), cluster.divergences(all));
            assertEquals(1, cluster.leaderOf(all));
        }
    }

    @Test
    void followerRejoiningWithoutRestartConverges() throws Exception {
        rejoinWithoutRestart(3);
    }

    @Test
    void leaderRejoiningWithoutRestartConverges() throws Exception {
        rejoinWithoutRestart(1);
    }

    @Test
    void oneWayLinkLossBetweenFollowersEvictsNobody() throws Exception {
        var inactivated = new CopyOnWriteArrayList<Integer>();
        var deleted = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(4, HEARTBEAT_MILLIS, LOST_SECONDS, 0, i -> new ClusterEvents()
                .inactivated("record", () -> inactivated.add(i))
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(i + ":" + nodeIndex)))) {
            cluster.startAll();
            var all = cluster.all();
            writeSome(cluster);
            cluster.awaitConsistent(all, Duration.ofSeconds(5));

            // node 4 no longer hears node 3; everyone else, the leader included, still does
            cluster.cut(3, 4);
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 5), () -> !cluster.node(4).getCluster().contains(3),
                    () -> "node 4 still counts node 3:" + cluster.describe(all));
            long until = System.nanoTime() + Duration.ofMillis(LOST_SECONDS * 1000L + 5L * HEARTBEAT_MILLIS).toNanos();
            while (System.nanoTime() < until) {
                for (int i : List.of(1, 2, 3))
                    assertEquals(Set.copyOf(all), cluster.node(i).getCluster(), "node " + i + " membership:" + cluster.describe(all));
                Thread.sleep(50);
            }
            // replicas still flow through the leader
            cluster.node(3).mergeSharedObject(Map.of("during", 1));
            cluster.awaitConsistent(all, Duration.ofSeconds(5));
            assertEquals(List.of(), inactivated, "nodes inactivated");
            assertEquals(List.of(), deleted, "cluster-deleted events");

            cluster.heal();
            awaitCluster(cluster, all, Set.copyOf(all), Duration.ofSeconds(5));
            cluster.awaitConsistent(all, Duration.ofSeconds(5));
            assertEquals(List.of(), inactivated, "nodes inactivated");
            assertEquals(List.of(), deleted, "cluster-deleted events");
        }
    }

    @Test
    void stalledNodeReArmsItsTimersWhileARealPartitionStillRemoves() throws Exception {
        var inactivated = new CopyOnWriteArrayList<Integer>();
        var deleted = new CopyOnWriteArrayList<String>();
        try (var cluster = new TestCluster(3, HEARTBEAT_MILLIS, LOST_SECONDS, 0, i -> new ClusterEvents()
                .inactivated("record", () -> inactivated.add(i))
                .clusterDeleted("record", (nodeIndex, object) -> deleted.add(i + ":" + nodeIndex)))) {
            cluster.startAll();
            var all = cluster.all();
            var stalled = cluster.service(2);

            // node 2's JVM is "paused": its stall ticker sleeps and it hears nobody for longer than the lost timeout,
            // while its own heartbeats still go out, as those queued in a paused JVM would on resume
            stalled.simulateStall(3500);
            Thread.sleep(600);
            cluster.cutInbound(2);
            boolean seenStalled = false;
            long until = System.nanoTime() + Duration.ofMillis(2500).toNanos();
            while (System.nanoTime() < until) {
                seenStalled |= stalled.recentlyStalled();
                assertEquals(Set.copyOf(all), cluster.node(2).getCluster(), "stalled node's membership:" + cluster.describe(all));
                Thread.sleep(50);
            }
            cluster.heal();
            until = System.nanoTime() + Duration.ofMillis(LOST_SECONDS * 1000L + 3L * HEARTBEAT_MILLIS).toNanos();
            while (System.nanoTime() < until) {
                assertEquals(Set.copyOf(all), cluster.node(2).getCluster(), "stalled node's membership:" + cluster.describe(all));
                Thread.sleep(50);
            }
            assertTrue(seenStalled);
            assertTrue(cluster.node(2).isActivated());
            assertEquals(Position.FOLLOWER, cluster.node(2).getPosition());
            assertEquals(1, cluster.leaderOf(all));
            assertEquals(List.of(), inactivated, "nodes inactivated");
            assertEquals(List.of(), deleted, "cluster-deleted events");

            // a real partition, without a stall, still removes the node
            cluster.partition(Set.of(3));
            TestCluster.await(Duration.ofSeconds(LOST_SECONDS + 5), () -> deleted.contains("1:3") && !cluster.node(1).getCluster().contains(3),
                    () -> "node 3 not removed by the leader:" + cluster.describe(all));
            cluster.heal();
            awaitCluster(cluster, all, Set.copyOf(all), Duration.ofSeconds(10));
            cluster.awaitConsistent(all, Duration.ofSeconds(10));
        }
    }
}
