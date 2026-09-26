package com.sds.communicators.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sds.communicators.common.type.Position;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * N in-process nodes on loopback whose node-to-node traffic runs through cuttable one-way {@link Link}s (node i reaches
 * node j only through links[i][j]), plus an oracle comparing every replica with its owner's own object.
 */
final class TestCluster implements AutoCloseable {
    private static final ObjectMapper CANONICAL = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Field CLUSTER_SERVICE;

    static {
        try {
            CLUSTER_SERVICE = ClusterStarter.class.getDeclaredField("clusterService");
            CLUSTER_SERVICE.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final int size;
    final int heartbeatMillis;
    final int lostSeconds;
    private final int quorum;
    private final int[] ports;
    private final Link[][] links;
    private final ClusterStarter[] nodes;
    private final IntFunction<ClusterEvents> events;
    private final Set<Integer> disposed = ConcurrentHashMap.newKeySet();

    TestCluster(int size, int heartbeatMillis, int lostSeconds, int quorum, IntFunction<ClusterEvents> events) throws Exception {
        this.size = size;
        this.heartbeatMillis = heartbeatMillis;
        this.lostSeconds = lostSeconds;
        this.quorum = quorum;
        this.events = events;
        ports = new int[size + 1];
        for (int i = 1; i <= size; i++)
            ports[i] = StubServer.freePort();
        links = new Link[size + 1][size + 1];
        for (int i = 1; i <= size; i++)
            for (int j = 1; j <= size; j++)
                if (i != j)
                    links[i][j] = new Link(ports[j]);
        nodes = new ClusterStarter[size + 1];
        for (int i = 1; i <= size; i++)
            nodes[i] = build(i);
    }

    private ClusterStarter build(int i) throws Exception {
        var urls = new HashSet<String>();
        urls.add("http://127.0.0.1:" + ports[i]);
        for (int j = 1; j <= size; j++)
            if (j != i)
                urls.add("http://127.0.0.1:" + links[i][j].port());
        var builder = ClusterStarter.builder(urls, ports[i], i)
                .setLeaderLostTimeoutSeconds(lostSeconds)
                .setHeartbeatSendingIntervalMillis(heartbeatMillis)
                .setQuorum(quorum)
                .setReadTimeoutMillis(10000);
        if (events != null)
            builder.setClusterEvents(events.apply(i));
        return builder.build();
    }

    /** starts every node in parallel (each waits lostSeconds for a leader) and waits until node 1 leads everyone */
    void startAll() throws Exception {
        var errors = new ConcurrentHashMap<Integer, Throwable>();
        var threads = new ArrayList<Thread>();
        for (int i = 1; i <= size; i++) {
            int index = i;
            var thread = new Thread(() -> {
                try {
                    nodes[index].start();
                } catch (Throwable e) {
                    errors.put(index, e);
                }
            });
            thread.start();
            threads.add(thread);
            // node 1 first, so that it is the one that starts as leader
            Thread.sleep(100);
        }
        for (var thread : threads)
            thread.join();
        if (!errors.isEmpty())
            throw new IllegalStateException("start failed: " + errors);
        var all = all();
        await(Duration.ofSeconds(10), () -> leaderOf(all) == 1 && all.stream().allMatch(i -> node(i).getCluster().size() == size),
                () -> "cluster not formed: " + describe(all));
    }

    ClusterStarter node(int i) {
        return nodes[i];
    }

    ClusterService service(int i) {
        try {
            return (ClusterService) CLUSTER_SERVICE.get(nodes[i]);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    List<Integer> all() {
        var all = new ArrayList<Integer>();
        for (int i = 1; i <= size; i++)
            all.add(i);
        return all;
    }

    /** the node the given nodes agree on as leader, 0 when there is no single one */
    int leaderOf(Collection<Integer> live) {
        int leader = 0;
        for (int i : live) {
            if (node(i).getPosition() == Position.LEADER) {
                if (leader != 0)
                    return 0;
                leader = i;
            }
        }
        return leader;
    }

    /** the node dies: its server and its timers are gone */
    void kill(int i) {
        disposed.add(i);
        nodes[i].dispose();
    }

    /** a new process for node i on the same port (blocks lostSeconds while it prepares) */
    void restart(int i) throws Throwable {
        if (!disposed.remove(i))
            nodes[i].dispose();
        nodes[i] = build(i);
        nodes[i].start();
    }

    /** cuts every link between a node of side and a node outside it, in both directions */
    void partition(Set<Integer> side) {
        for (int i = 1; i <= size; i++)
            for (int j = 1; j <= size; j++)
                if (i != j && side.contains(i) != side.contains(j))
                    links[i][j].setCut(true);
    }

    void cut(int from, int to) {
        links[from][to].setCut(true);
    }

    /** restores the links between a and b, in both directions */
    void restore(int a, int b) {
        links[a][b].setCut(false);
        links[b][a].setCut(false);
    }

    /** node i no longer hears anyone, while everyone still hears node i */
    void cutInbound(int i) {
        for (int j = 1; j <= size; j++)
            if (j != i)
                links[j][i].setCut(true);
    }

    /** restores the links from each node of side to the nodes outside it, while those back to side stay as they are */
    void healFrom(Set<Integer> side) {
        for (int i : side)
            for (int j = 1; j <= size; j++)
                if (i != j && !side.contains(j))
                    links[i][j].setCut(false);
    }

    void heal() {
        for (int i = 1; i <= size; i++)
            for (int j = 1; j <= size; j++)
                if (i != j)
                    links[i][j].setCut(false);
    }

    static String json(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** every live node's replica of every live owner must equal the owner's own object and sequence */
    List<String> divergences(Collection<Integer> live) {
        var snapshots = new HashMap<Integer, ClusterService.SharedObject>();
        for (int i : live)
            snapshots.put(i, service(i).copySharedObject(false));
        var out = new ArrayList<String>();
        for (int owner : live) {
            var own = snapshots.get(owner);
            String ownObject = json(own.sharedObject.get(owner));
            Long ownSeq = own.sharedObjectSeq.get(owner);
            for (int holder : live) {
                if (holder == owner)
                    continue;
                var replica = snapshots.get(holder);
                String object = json(replica.sharedObject.get(owner));
                Long seq = replica.sharedObjectSeq.get(owner);
                if (!Objects.equals(ownObject, object) || !Objects.equals(ownSeq, seq))
                    out.add(String.format("node%d's replica of node%d: seq=%s obj=%s | owner seq=%s obj=%s", holder, owner, seq, object, ownSeq, ownObject));
            }
        }
        return out;
    }

    void awaitConsistent(Collection<Integer> live, Duration timeout) {
        await(timeout, () -> divergences(live).isEmpty(), () -> "replicas diverge: " + divergences(live) + "\n" + describe(live));
    }

    /** node-indexes of the replicas node i holds */
    Set<Integer> replicas(int i) {
        return service(i).copySharedObjectSeq().keySet();
    }

    String describe(Collection<Integer> live) {
        var out = new StringBuilder();
        for (int i : live) {
            var snapshot = service(i).copySharedObject(false);
            out.append(String.format("%n  node%d pos=%s act=%s cluster=%s seq=%s obj=%s", i, node(i).getPosition(), node(i).isActivated(),
                    new TreeSet<>(node(i).getCluster()), json(snapshot.sharedObjectSeq), json(snapshot.sharedObject)));
        }
        return out.toString();
    }

    static void await(Duration timeout, BooleanSupplier condition, Supplier<String> message) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline)
                fail(message.get());
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    @Override
    public void close() {
        for (int i = 1; i <= size; i++) {
            try {
                nodes[i].dispose();
            } catch (Throwable ignored) {
            }
        }
        for (int i = 1; i <= size; i++)
            for (int j = 1; j <= size; j++)
                if (i != j)
                    links[i][j].close();
    }
}
