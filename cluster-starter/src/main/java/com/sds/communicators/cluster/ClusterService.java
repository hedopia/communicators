package com.sds.communicators.cluster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.Position;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
class ClusterService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    /** sorted keys: equal content hashes the same on every node, whatever the iteration order of its maps */
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final long STALL_TICK_MILLIS = 100;
    private static final long STALL_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(1000, 10 * STALL_TICK_MILLIS));
    private static final int ANTI_ENTROPY_TICKS = 5;
    /**
     * a sender's changes waiting for one peer beyond this many are replaced by one copy of its whole replica. Not lowered
     * with batching: a lane holds what arrived while its previous batch was on its way, which for a peer that keeps up
     * is one change per writer waiting on the leader (many device scripts may write at once), and a whole copy costs
     * every follower an overwrite and an overwritten event. A lagging peer, whose batches take up to the probe timeout,
     * passes the bound anyway under a steady write rate, and then gets one whole copy per batch. 256 small changes are
     * one request of some tens of KB, about a driver's whole object
     */
    private static final int MAX_PENDING_PROPAGATIONS = 256;
    /** a peer that answered the batch route with 404 (older version) gets one change per call; the batch route is tried again after this */
    private static final long PER_CHANGE_RECHECK_NANOS = TimeUnit.MINUTES.toNanos(1);
    /** failed handovers in a row beyond this do not lengthen the wait before the next one (2^n heartbeat intervals) */
    private static final int MAX_HANDOVER_BACKOFF_EXPONENT = 4;
    /** the body of a 404 answer to a request for a replica that the node does not hold */
    static final String ABSENT = "absent";

    private final ClusterStarter clusterStarter;
    private final ClusterRedirectFunction redirectFunction;
    private final ClusterInternalClient client;

    final ClusterEvents clusterEvents = new ClusterEvents();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final PublishProcessor<Position> leaderTimer = PublishProcessor.create();
    private final PublishProcessor<Integer> nodeTimer = PublishProcessor.create();
    /**
     * replicas: a node's entry is either in both maps or in neither. Their contents, nested maps included, are read and
     * changed only under replicaLock, and only copies leave this class
     */
    final Map<Integer, Map<String, Object>> sharedObject = new ConcurrentHashMap<>();
    final Map<Integer, Long> sharedObjectSeq = new ConcurrentHashMap<>();
    /**
     * leader: replicas of removed nodes whose cluster-deleted events (failover) are still running. Out of the registration
     * view, but still listed to the followers, which keep their copy until the failover has finished (guarded by replicaLock)
     */
    private final Map<Integer, MergeSharedObjectInfo> pendingRemovals = new HashMap<>();
    /**
     * per node, the sequence of the newest object known to be removed: failed over here by a leader that led all the
     * while, or learned from such a leader's cluster-deleted broadcast (as follower or leader), or, as follower, dropped
     * because the leader that listed it held none. An offered or handed-over copy is taken over only if newer (guarded by
     * replicaLock)
     */
    private final Map<Integer, Long> removedSeqs = new HashMap<>();
    /**
     * per node, the sequence of the newest object whose failover finished on a node that no longer led (see
     * {@link #completeRemoval}): this node's own, or learned from that node's marked broadcast or its handover. Not proof
     * that the devices were placed: the leader may have held that object meanwhile, and refused what the failover placed
     * through it. A leader that learns it while leading declines that object at that sequence while it leads (see
     * {@link #declinedSeq}); a follower keeps a copy that came from that leader, which is offered to a later leader and
     * failed over by a node that leads later (see {@link #replicaDeleted(int, Long, boolean, int)}); the node whose
     * failover it was holds the copy it handed over again once the leader has taken that over (see {@link #holdAgain});
     * a copy no longer held is handed over or offered no more (see {@link #removedHere}) (guarded by replicaLock)
     */
    private final Map<Integer, SoftRemoval> softRemovals = new HashMap<>();
    /**
     * follower: per node, the leader whose heartbeat listed this node's replica at its sequence (or a newer one), and
     * that sequence. A replica that the same leader no longer lists was removed by it; one that a leader never listed (a
     * new or restarted leader, or a split-brain leader) was not (guarded by replicaLock)
     */
    private final Map<Integer, Listing> listings = new HashMap<>();

    /** a new instance with each transition: a removal tells by it whether this node led all the while */
    private volatile ZonedDateTime lastTransitionTime = ZonedDateTime.now();
    private int maxClusterSize = 0;
    private boolean initialPosition = true;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Disposable> nodes = new ConcurrentHashMap<>();
    private final Object setSharedObjectMutex = new Object();
    private final Object syncMutex = new Object();
    /** lock order: setSharedObjectMutex -> syncMutex -> replicaLock. Held for in-memory work only: never across HTTP, a sleep or a wait */
    private final Object replicaLock = new Object();
    /**
     * leader-side fan-out of shared-object changes: a virtual thread per lane that has changes to send, so a sender's
     * changes never queue behind another sender's, nor one peer's behind another's (only HTTP and replica copies run
     * there, no polyglot context, so the restriction described in RouteDispatcher does not apply)
     */
    private final ExecutorService propagationExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("shared-object-propagation-", 0).factory());
    /**
     * per sender and peer url, the changes on their way to that peer, in sequence order: one batch at a time (guarded by
     * itself, which is also the monitor the leader's own changes wait on)
     */
    private final Map<Integer, Map<String, PropagationLane>> propagationLanes = new HashMap<>();
    /** peers that answered the batch route with 404 (older version), with when (nanoTime): sent one change per call */
    private final Map<String, Long> perChangeUrls = new ConcurrentHashMap<>();
    /** peers whose last heartbeat or propagation call succeeded: only they are sent changes */
    private final Set<String> reachableUrls = ConcurrentHashMap.newKeySet();
    /**
     * url -> node-index, as the node answered this node's heartbeats at that url (a node of an older version answers
     * nothing): a node is reached there without asking every url for its status first
     */
    private final Map<String, Integer> urlIndexes = new ConcurrentHashMap<>();
    /** node-index -> when (nanoTime) a heartbeat of this node last got through to that node (it answered with its index) */
    private final Map<Integer, Long> heartbeatsReached = new ConcurrentHashMap<>();
    /** leader-side removals: each waits for its failover, so one slow failover does not hold up another */
    private final ExecutorService removalExecutor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "shared-object-removal");
        thread.setDaemon(true);
        return thread;
    });
    /** leader: since when (nanoTime) a held replica's node has not been a member */
    private final Map<Integer, Long> absentSince = new ConcurrentHashMap<>();
    /**
     * leader: when (nanoTime) the removal of a node that is not a member was last requested (by this node's timer, or by
     * a follower whose timer expired), until the node is heard again: a copy of it offered within the absence grace after
     * that is removed right away, as the request would have removed it had it been offered a moment earlier (see
     * {@link #otherLeaderHeard} for when not)
     */
    private final Map<Integer, Long> removalsRequested = new ConcurrentHashMap<>();
    /**
     * leader: when (nanoTime) another leader's heartbeat last arrived while this node led (transient dual leaders), null
     * if none did: for the absence grace after that, an offered copy is not removed right away on a request, since the
     * other leader may be failing that node over too, and its broadcast would make this node drop the copy meanwhile
     */
    private volatile Long otherLeaderHeard = null;
    /** the leader whose heartbeat this follower received last */
    private volatile int leaderIndex = 0;
    /** heartbeat thread only */
    private int heartbeatTicks = 0;
    private final AtomicBoolean antiEntropyRunning = new AtomicBoolean(false);
    /**
     * replicas this follower hands over to the leader until it has them: a demoted split-brain leader's, or those a new
     * leader lacks. While set, a leader heartbeat sends them again (with backoff), and the reconcile neither drops them
     * nor replaces them with an older copy (a node that died where only this node heard it would otherwise never be
     * failed over)
     */
    private final AtomicReference<Handover> pendingHandover = new AtomicReference<>();
    /** one handover call in flight at a time */
    private final AtomicBoolean handoverRunning = new AtomicBoolean(false);
    /** backoff of handover calls, kept across handovers to the same leader while this node's heartbeats do not reach it */
    private final HandoverBackoff handoverBackoff = new HandoverBackoff();
    /** the last leader a handover call did not find (its url unknown here): logged at WARN once per leader */
    private final AtomicReference<LeaderId> unfoundLeader = new AtomicReference<>();
    /** the leader whose heartbeats this follower reconciles with: index and transition time (a restarted leader is another one) */
    private final Object followedLeaderLock = new Object();
    private LeaderId followedLeader = null;
    /**
     * leaders whose heartbeats arrived lately, with when (nanoTime): one heard again within the leader-lost timeout is
     * not a new leader, which is what a node hearing two split-brain leaders alternately sees (guarded by followedLeaderLock)
     */
    private final Map<LeaderId, Long> recentLeaders = new HashMap<>();
    /** split-brain winner: per demoted node, its last handover handled, so a repeated one does not resolve the split brain again */
    private final Map<Integer, String> handledHandovers = new ConcurrentHashMap<>();
    /** set by dispose, reset by start: requests and timers still under way change nothing then */
    private volatile boolean disposed = false;

    private volatile Thread stallTicker;
    private volatile long lastTickNanos;
    private volatile long lastStallEndNanos;
    private volatile boolean stallSeen = false;
    private volatile long simulatedStallMillis = 0;

    ClusterService(ClusterStarter clusterStarter, ClusterRedirectFunction redirectFunction, ClusterInternalClient client) {
        this.clusterStarter = clusterStarter;
        this.redirectFunction = redirectFunction;
        this.client = client;
    }

    void start() throws InterruptedException {
        disposed = false;
        synchronized (replicaLock) {
            sharedObject.put(clusterStarter.nodeIndex, new HashMap<>());
            // not 0: replicas of this node's previous run may still hold its low sequence numbers, and an equal
            // sequence number is taken for equal content
            sharedObjectSeq.put(clusterStarter.nodeIndex, System.currentTimeMillis() * 1000);
        }
        startStallTicker();
        long initialDelay = clusterStarter.leaderLostTimeoutSeconds;
        log.info("cluster application preparing for {}[sec]", initialDelay);

        Thread.sleep(initialDelay * 1000);

        if (clusterStarter.nodeIndex == 1 && initialPosition)
            transition(Position.LEADER);
        else
            transition(Position.FOLLOWER);
        nodes.put(clusterStarter.nodeIndex,
                Schedulers.newThread()
                        .schedulePeriodicallyDirect(this::heartbeat,
                                clusterStarter.heartbeatSendingIntervalMillis,
                                clusterStarter.heartbeatSendingIntervalMillis,
                                TimeUnit.MILLISECONDS)
        );
        verifyActivation();

        clusterStarter.isPrepared = true;
        log.info("cluster application prepared");
    }

    void dispose() {
        // first: a heartbeat still being handled, or a timer firing meanwhile, must not add, remove or fail over nodes
        disposed = true;
        initialPosition = true;
        stopStallTicker();
        // a lane still sending finds disposed set and stops after its current batch
        synchronized (propagationLanes) {
            for (var lanes : propagationLanes.values()) {
                for (var lane : lanes.values()) {
                    lane.changes.clear();
                    lane.wholeReplica = false;
                }
            }
            propagationLanes.clear();
            propagationLanes.notifyAll();
        }
        perChangeUrls.clear();
        pendingHandover.set(null);
        handoverBackoff.reset();
        unfoundLeader.set(null);
        synchronized (followedLeaderLock) {
            followedLeader = null;
            recentLeaders.clear();
        }
        handledHandovers.clear();
        disposables.clear();
        synchronized (replicaLock) {
            sharedObject.clear();
            sharedObjectSeq.clear();
            // a removal whose events are still running then finds no entry and skips its broadcast
            pendingRemovals.clear();
            removedSeqs.clear();
            softRemovals.clear();
            listings.clear();
        }
        absentSince.clear();
        removalsRequested.clear();
        otherLeaderHeard = null;
        reachableUrls.clear();
        urlIndexes.clear();
        heartbeatsReached.clear();
        for (var node : nodes.values())
            node.dispose();
        nodes.clear();
    }

    void forceToLeader() {
        if (clusterStarter.isPrepared)
            transition(Position.LEADER);
        else
            log.error("application is not prepared, force to leader ignored");
    }

    void forceToFollower() {
        if (clusterStarter.isPrepared)
            transition(Position.FOLLOWER);
        else
            log.error("application is not prepared, force to follower ignored");
    }

    Set<Integer> getCluster() {
        return nodes.keySet();
    }

    Position getPosition(int nodeIndex) throws Throwable {
        AtomicReference<Position> ret = new AtomicReference<>();
        var result = redirectFunction.toIndexFunc(nodeIndex, targetUrl ->
                ret.set(client.getNodeStatus(targetUrl).getPosition()), "get position for node-index: " + nodeIndex);
        if (result != null) throw result;
        return ret.get();
    }

    private void heartbeat() {
        log.info("position: {} (last transition time: {})", clusterStarter.position, lastTransitionTime);
        Schedulers.io().scheduleDirect(() -> {
            try {
                long begin = System.currentTimeMillis();
                // sent without syncMutex: syncMutex is held across HTTP calls (shared-object sync), and waiting
                // on it or on a peer that does not answer must not delay heartbeats to the other nodes
                sendHeartbeat(clusterStarter.position == Position.LEADER ? Position.LEADER : Position.FOLLOWER,
                        heartbeatSequences());
                log.trace("send heartbeat success, elapsed time: {}[ms]", System.currentTimeMillis() - begin);
            } catch (Exception e) {
                log.error("send heartbeat failed::{}", e.getMessage());
            }
        });
        try {
            if (clusterStarter.position == Position.LEADER)
                removeAbsentReplicas();
            else
                absentSince.clear();
        } catch (Exception e) {
            log.error("remove absent replicas failed", e);
        }
        if (++heartbeatTicks % ANTI_ENTROPY_TICKS == 0)
            Schedulers.io().scheduleDirect(this::antiEntropy);
    }

    private void sendHeartbeat(Position position, Map<Integer, Long> sharedObjectSeq) {
        redirectFunction.toAllFunc(targetUrl -> {
            try {
                var index = client.heartbeat(
                        targetUrl,
                        clusterStarter.nodeIndex,
                        position,
                        lastTransitionTime.toInstant().toEpochMilli(),
                        sharedObjectSeq);
                reachableUrls.add(targetUrl);
                if (index != null && index != clusterStarter.nodeIndex) {
                    urlIndexes.put(targetUrl, index);
                    long now = System.nanoTime();
                    var last = heartbeatsReached.put(index, now);
                    // reached again after a gap (its heartbeats failed, or none was sent to it): a handover to that
                    // leader is due right away, whatever failed while this node could not reach it
                    if (last == null || now - last > TimeUnit.MILLISECONDS.toNanos(2L * clusterStarter.heartbeatSendingIntervalMillis))
                        handoverBackoff.reached(index);
                }
            } catch (Throwable e) {
                reachableUrls.remove(targetUrl);
                throw e;
            }
        }, "send heartbeat");
    }

    void transition(Position position) {
        if (lock.tryLock()) {
            try {
                if (clusterStarter.position != position) {
                    log.info("position changed {} => {}", clusterStarter.position, position);
                    clusterStarter.position = position;
                    lastTransitionTime = ZonedDateTime.now();
                    disposables.clear();
                    if (position == Position.LEADER) {
                        absentSince.clear();
                        // this node's replicas are the reference again
                        pendingHandover.set(null);
                        // whichever leader this node follows next is offered what only this node holds
                        synchronized (followedLeaderLock) {
                            followedLeader = null;
                            recentLeaders.clear();
                        }
                        sendHeartbeat(Position.LEADER, heartbeatSequences());
                        ClusterEvents.fireEvents(clusterEvents.becomeLeaderEvents, "become leader");
                    } else {
                        ClusterEvents.fireEvents(clusterEvents.becomeFollowerEvents, "become follower");
                        armLeaderTimer();
                    }
                } else {
                    log.info("position is already {}", position);
                }
            } catch (Exception e) {
                log.error("transition processing error", e);
            } finally {
                lock.unlock();
            }
        } else {
            log.debug("transition to {} ignored, because of already processing", position);
        }
    }

    private void armLeaderTimer() {
        disposables.add(
                leaderTimer.filter(p -> p == Position.LEADER)
                        .timeout(clusterStarter.leaderLostTimeoutSeconds, TimeUnit.SECONDS, Schedulers.io())
                        .subscribe(__ -> log.trace("Heartbeat received from leader"),
                                e -> leaderLost())
        );
    }

    private void leaderLost() {
        if (disposed)
            return;
        if (recentlyStalled()) {
            log.warn("leader heartbeat timed out while this node was stalled, leader timer re-armed");
            if (clusterStarter.position != Position.LEADER)
                armLeaderTimer();
            return;
        }
        redirectFunction.electLeader();
    }

    private Disposable armNodeTimer(int nodeIndex) {
        return nodeTimer.filter(r -> r.equals(nodeIndex))
                .timeout(clusterStarter.leaderLostTimeoutSeconds, TimeUnit.SECONDS, Schedulers.io())
                .subscribe(index -> log.trace("Heartbeat received from node-index: {}", index)
                        , e -> nodeLost(nodeIndex));
    }

    private void nodeLost(int nodeIndex) {
        if (disposed) {
            log.debug("heartbeat of node-index: {} timed out after dispose, ignored", nodeIndex);
            return;
        }
        if (recentlyStalled()) {
            log.warn("heartbeat of node-index: {} timed out while this node was stalled, timer re-armed", nodeIndex);
            nodes.computeIfPresent(nodeIndex, (key, expired) -> armNodeTimer(key));
            return;
        }
        clusterDeleted(nodeIndex);
    }

    void heartbeatReceived(int nodeIndex, Position position, long lastTransitionTime, Map<Integer, Long> receivedSharedObjectSeq) {
        if (disposed) {
            log.debug("heartbeat from node-index: {} ignored, disposed", nodeIndex);
            return;
        }
        log.debug("heartbeat received from node-index: {}, position: {}", nodeIndex, position);
        if (initialPosition && position == Position.LEADER)
            initialPosition = false;

        boolean demoted = false;
        if (clusterStarter.position == Position.LEADER &&
                position == Position.LEADER &&
                (lastTransitionTime < this.lastTransitionTime.toInstant().toEpochMilli() ||
                        (lastTransitionTime == this.lastTransitionTime.toInstant().toEpochMilli() && nodeIndex < clusterStarter.nodeIndex))) {
            log.warn("unexpected leader heartbeat received from node-index: {}, set to follower", nodeIndex);
            transition(Position.FOLLOWER);
            demoted = true;
            handOverSplitBrain(nodeIndex, lastTransitionTime);
        } else if (clusterStarter.position == Position.LEADER && position == Position.LEADER) {
            // two leaders at once, and this one stays: the other one may be failing over the same nodes
            otherLeaderHeard = System.nanoTime();
        }

        leaderTimer.onNext(position);

        if (nodes.containsKey(nodeIndex))
            nodeTimer.onNext(nodeIndex);
        else
            clusterAdded(nodeIndex);

        // not on a leader, which would fetch from itself and keep whatever it holds, including nothing. A node still
        // preparing (no position yet) does sync: it is electable, and a leader without the replicas makes the followers drop them
        if (position == Position.LEADER && clusterStarter.position != Position.LEADER && receivedSharedObjectSeq != null) {
            leaderIndex = nodeIndex;
            var handingOver = followLeader(nodeIndex, lastTransitionTime, receivedSharedObjectSeq, demoted);
            // the heartbeat that demoted this node was sent before the leader took over its replicas: the next one decides
            if (!demoted)
                syncWithLeader(new LeaderId(nodeIndex, lastTransitionTime), receivedSharedObjectSeq, handingOver);
        }

        if (clusterStarter.position == Position.LEADER) {
            var seq = replicaSeq(nodeIndex);
            if (seq == null || receivedSharedObjectSeq == null || !seq.equals(receivedSharedObjectSeq.get(nodeIndex))) {
                synchronized (syncMutex) {
                    overwriteLeaderSharedObject(nodeIndex);
                }
            }
        }
    }

    /**
     * a demoted split-brain leader hands everything it holds over to the winner, replicas whose failover still runs here
     * included (the winner fails them over itself; once that failover has finished, a call sends the sequence removed
     * instead, see {@link #removedSince}): kept until delivered, and sent off the heartbeat thread, one call at a
     * time. Stored under followedLeaderLock, as offers are: an offer made meanwhile for a concurrent heartbeat of the
     * winner is replaced, never the other way round
     */
    private void handOverSplitBrain(int leaderIndex, long leaderTransitionTime) {
        // the transition was skipped (another one under way): this node still leads
        if (clusterStarter.position == Position.LEADER)
            return;
        var target = new LeaderId(leaderIndex, leaderTransitionTime);
        SharedObject object;
        synchronized (syncMutex) {
            object = copySharedObject(true);
        }
        Handover handover;
        synchronized (followedLeaderLock) {
            var pending = pendingHandover.get();
            // demoted by two heartbeats of that leader at once: one handover, which resolves the split brain once
            if (pending != null && !pending.offer && pending.target.equals(target)) {
                log.debug("{} to node-index: {} pending already", pending, leaderIndex);
                return;
            }
            handover = new Handover(false, object, target);
            pendingHandover.set(handover);
        }
        sendHandover(handover);
    }

    /**
     * follower, on a leader heartbeat: the replicas still being handed over to that leader (node-index -> sequence), which
     * the reconcile keeps meanwhile. A leader other than the one followed so far (leader change, heal, restart of the
     * leader) is first offered the replicas it lacks or holds older, before any of them is dropped because it lacks them;
     * with the same leader, its lack of a replica decides. A leader heard again within the leader-lost timeout (two
     * split-brain leaders heard alternately) is no new one. What was handed over to a leader is delivered only against
     * that leader's heartbeats, and kept for it while it is heard: another leader, which may hold or list what that one
     * lacks, decides nothing about it then. Once that leader has not been heard for the leader-lost timeout, or sooner
     * once it has missed two heartbeats here and the leader followed now was elected after its last one here (it took
     * over from that one, which is gone), the leader followed now is offered what it lacks instead.
     * @param demoted the heartbeat demoted this node: it was sent before the winner took over its replicas, so it decides nothing
     */
    private Map<Integer, Long> followLeader(int leaderIndex, long leaderTransitionTime, Map<Integer, Long> leaderSeq, boolean demoted) {
        var leader = new LeaderId(leaderIndex, leaderTransitionTime);
        Handover handover;
        synchronized (followedLeaderLock) {
            boolean heardLately = heardLately(leader);
            boolean otherLeader = !leader.equals(followedLeader) && !heardLately;
            followedLeader = leader;
            handover = pendingHandover.get();
            if (handover != null && !handover.target.equals(leader)) {
                // heard alternately with the leader it goes to (a bridge between split-brain leaders): kept for that one,
                // whose own heartbeats decide, and sent once the nodes of the copies are no longer heard
                var targetHeard = recentLeaders.get(handover.target);
                if (targetHeard != null && !tookOver(leader, targetHeard))
                    return handover.held();
                // the leader it went to is gone, or another one took over meanwhile: this one is offered what it
                // lacks (nothing when it holds everything), and nothing is dropped meanwhile
                var offer = offerFor(leader, leaderSeq);
                if (pendingHandover.compareAndSet(handover, offer))
                    log.info("{} not delivered, node-index: {} leads now, {}", handover, leaderIndex, offer == null ? "which holds them or removed them" : offer);
                handover = offer;
                otherLeader = false;
            }
            if (handover == null && otherLeader) {
                handover = offerFor(leader, leaderSeq);
                // pending was read under this lock, where a split-brain handover is stored too: an offer never replaces one
                if (handover != null) {
                    if (pendingHandover.compareAndSet(null, handover))
                        log.info("node-index: {} leads now, {}", leaderIndex, handover);
                    else
                        handover = null;
                }
            }
        }
        if (handover == null)
            return Map.of();
        // the first call is under way: the winner is told in any case, and resolves the split brain once
        if (demoted)
            return handover.held();
        if (handover.covered || covers(leaderSeq, handover)) {
            if (!handover.offer && !handover.delivered) {
                // the winner holds them all, but was not told yet: nothing is kept for it any more, and it is told
                // (without the objects, with the sequences of those removed here since), so that it resolves the split brain
                if (!handover.covered) {
                    handover.covered = true;
                    log.info("{}: the leader holds them or removed them, it is told so", handover);
                }
                sendHandover(handover);
                return Map.of();
            }
            if (pendingHandover.compareAndSet(handover, null))
                log.info("{} delivered, the leader holds them or removed them", handover);
            return Map.of();
        }
        if (handover.delivered || (handover.offer && unanswered(leaderSeq, handover).isEmpty())) {
            // answered, but this heartbeat may have been sent before the leader took them over: kept this once more
            if (pendingHandover.compareAndSet(handover, null))
                log.info("{} delivered", handover);
            return handover.held();
        }
        sendHandover(handover);
        return handover.held();
    }

    /** under followedLeaderLock: whether a heartbeat of leader arrived within the leader-lost timeout before this one, which is recorded */
    private boolean heardLately(LeaderId leader) {
        long now = System.nanoTime();
        long window = Math.max(TimeUnit.SECONDS.toNanos(clusterStarter.leaderLostTimeoutSeconds),
                TimeUnit.MILLISECONDS.toNanos(2L * clusterStarter.heartbeatSendingIntervalMillis));
        recentLeaders.values().removeIf(heard -> now - heard > window);
        return recentLeaders.put(leader, now) != null;
    }

    /**
     * under followedLeaderLock: leader was elected after the last heartbeat of another leader heard here (at heardNanos),
     * and that one has missed two heartbeats since: leader took over from it, and it is gone (it died, or restarted as
     * another leader). Not so with two split-brain leaders heard alternately (a bridge): each is heard every interval, so
     * one elected meanwhile is heard while the other one misses no two heartbeats, and led before its next one
     */
    private boolean tookOver(LeaderId leader, long heardNanos) {
        long silentNanos = System.nanoTime() - heardNanos;
        if (silentNanos <= TimeUnit.MILLISECONDS.toNanos(2L * clusterStarter.heartbeatSendingIntervalMillis))
            return false;
        return leader.transitionTime() > System.currentTimeMillis() - TimeUnit.NANOSECONDS.toMillis(silentNanos);
    }

    /**
     * the replicas a new leader lacks or holds older (null when there are none), offered so that it fails over those of
     * nodes that are gone. Those of nodes this node hears are kept for it, but not sent while this node hears them: a
     * live node supplies its own object, and the leader may just not hear it (split brain)
     */
    private Handover offerFor(LeaderId target, Map<Integer, Long> leaderSeq) {
        List<Integer> offered = new ArrayList<>();
        synchronized (replicaLock) {
            var held = new HashMap<>(sharedObjectSeq);
            for (var entry : pendingRemovals.entrySet())
                held.putIfAbsent(entry.getKey(), entry.getValue().seq);
            for (var entry : held.entrySet()) {
                var leader = leaderSeq.get(entry.getKey());
                // a copy known to be removed (failed over) is not offered: only one newer than that, or one still held
                // after a demoted leader's failover of it (see removedHere)
                if (entry.getKey() != clusterStarter.nodeIndex && (leader == null || leader < entry.getValue()) &&
                        !removedHere(entry.getKey(), entry.getValue()))
                    offered.add(entry.getKey());
            }
        }
        Map<Integer, Map<String, Object>> objects = new HashMap<>();
        Map<Integer, Long> sequences = new HashMap<>();
        for (int nodeIndex : offered) {
            var copy = copyReplica(nodeIndex, true);
            if (copy != null) {
                objects.put(nodeIndex, copy.obj);
                sequences.put(nodeIndex, copy.seq);
            }
        }
        return objects.isEmpty() ? null : new Handover(true, new SharedObject(objects, sequences), target);
    }

    /**
     * delivered once a leader heartbeat lists every handed-over replica (this node's own aside) at the same or a newer
     * sequence, or it is known to be removed at such a sequence (see {@link #removedHere}): the leader has them, whether
     * or not the call was answered
     */
    private boolean covers(Map<Integer, Long> leaderSeq, Handover handover) {
        synchronized (replicaLock) {
            for (var entry : handover.object.sharedObjectSeq.entrySet()) {
                if (entry.getKey() == clusterStarter.nodeIndex)
                    continue;
                var held = leaderSeq.get(entry.getKey());
                if ((held == null || held < entry.getValue()) && !removedHere(entry.getKey(), entry.getValue()))
                    return false;
            }
            return true;
        }
    }

    /**
     * split brain: node-index -> sequence known to be removed of the handed-over replicas that this node no longer holds
     * since, at their sequence or a newer one: their failover finished here after the demotion (or this node learned of
     * one elsewhere, from a leader that led all the while or not). They are handed over without their object, as that
     * sequence
     */
    private Map<Integer, Long> removedSince(Handover handover) {
        Map<Integer, Long> removedSince = new HashMap<>();
        synchronized (replicaLock) {
            for (var entry : handover.object.sharedObjectSeq.entrySet()) {
                int nodeIndex = entry.getKey();
                var removed = removedSeq(nodeIndex);
                if (nodeIndex != clusterStarter.nodeIndex && removed != null && removed >= entry.getValue() &&
                        !sharedObject.containsKey(nodeIndex) && !pendingRemovals.containsKey(nodeIndex))
                    removedSince.put(nodeIndex, removed);
            }
        }
        return removedSince;
    }

    /** offer: the replicas the leader has neither answered for nor lists (or that are known to be removed) at their sequence */
    private Set<Integer> unanswered(Map<Integer, Long> leaderSeq, Handover handover) {
        Set<Integer> unanswered = new HashSet<>();
        synchronized (replicaLock) {
            for (var entry : handover.object.sharedObjectSeq.entrySet()) {
                var held = leaderSeq.get(entry.getKey());
                if (entry.getKey() != clusterStarter.nodeIndex && !handover.answered.contains(entry.getKey()) &&
                        (held == null || held < entry.getValue()) && !removedHere(entry.getKey(), entry.getValue()))
                    unanswered.add(entry.getKey());
            }
        }
        return unanswered;
    }

    /**
     * offer: the replicas to send now, those not answered for yet of nodes this node does not hear, and not known to be
     * removed at their sequence meanwhile (a broadcast that arrived after the offer was made, see {@link #removedHere})
     */
    private SharedObject toOffer(Handover handover) {
        Map<Integer, Map<String, Object>> objects = new HashMap<>();
        Map<Integer, Long> sequences = new HashMap<>();
        synchronized (replicaLock) {
            for (var entry : handover.object.sharedObjectSeq.entrySet()) {
                int nodeIndex = entry.getKey();
                if (!handover.answered.contains(nodeIndex) && !nodes.containsKey(nodeIndex) && !removedHere(nodeIndex, entry.getValue())) {
                    objects.put(nodeIndex, handover.object.sharedObject.get(nodeIndex));
                    sequences.put(nodeIndex, entry.getValue());
                }
            }
        }
        return new SharedObject(objects, sequences);
    }

    /** object without the replicas of left (the same object when there are none) */
    private static SharedObject without(SharedObject object, Set<Integer> left) {
        if (left.isEmpty())
            return object;
        var objects = new HashMap<>(object.sharedObject);
        var sequences = new HashMap<>(object.sharedObjectSeq);
        objects.keySet().removeAll(left);
        sequences.keySet().removeAll(left);
        return new SharedObject(objects, sequences);
    }

    /**
     * sends the handover off the heartbeat thread, unless a call is already in flight or the backoff for its leader has
     * not elapsed (the first call of a split-brain handover goes out anyway), or there is nothing to offer yet
     */
    private void sendHandover(Handover handover) {
        if (handover.delivered || (handover.offer && toOffer(handover).sharedObject.isEmpty()))
            return;
        if ((handover.attempted || handover.offer) && !handoverBackoff.due(handover.target))
            return;
        if (!handoverRunning.compareAndSet(false, true))
            return;
        try {
            Schedulers.io().scheduleDirect(() -> {
                try {
                    if (disposed || handover.delivered)
                        return;
                    if (pendingHandover.get() == handover)
                        deliver(handover);
                    else
                        log.debug("{} not sent, it is no longer pending (replaced or cleared meanwhile)", handover);
                } finally {
                    handoverRunning.set(false);
                }
            });
        } catch (RuntimeException e) {
            handoverRunning.set(false);
            log.error("{} not sent::{}", handover, e.getMessage());
        }
    }

    private void deliver(Handover handover) {
        String name = handover.offer ? "offer shared-object" : "synchronize split brain leader shared-object";
        int leaderIndex = handover.target.index();
        var url = urlOf(leaderIndex);
        // once the winner holds them all, it is only told: it resolves the split brain then. A replica whose failover
        // finished here since goes as the sequence removed instead: the winner declines that object from then on, also
        // from a follower that missed this node's broadcast of that failover (sent where the winner did not hear it)
        Map<Integer, Long> removed;
        SharedObject sent;
        if (handover.offer) {
            removed = Map.of();
            sent = toOffer(handover);
        } else {
            synchronized (replicaLock) {
                removed = removedSince(handover);
                sent = handover.covered ? new SharedObject(Map.of(), Map.of()) : without(handover.object, removed.keySet());
                // a winner that this node's heartbeats reach may take them over any moment now: a failover of one of them
                // that finishes here meanwhile leaves this node holding it again (see completeRemoval)
                if (url != null && reachableUrls.contains(url))
                    handover.sending = sent.sharedObjectSeq.keySet();
            }
        }
        if (handover.offer && sent.sharedObject.isEmpty())
            return;
        handover.attempted = true;
        log.debug("{}: send {} to node-index: {}{}", handover, sent.sharedObjectSeq.keySet(), leaderIndex, removed.isEmpty() ? "" : ", removed: " + removed);
        var declined = new AtomicReference<Set<Integer>>(Set.of());
        var ret = toNode(leaderIndex, url, targetUrl -> {
            if (handover.offer)
                declined.set(client.offerSharedObject(targetUrl, clusterStarter.nodeIndex, sent));
            else
                client.syncSharedObject(targetUrl, clusterStarter.nodeIndex, sent, handover.id, removed);
        }, name);
        if (!handover.offer)
            handedOver(handover, sent, ret == null);
        // a leader of an older version takes no offers: nothing more to do then. One that answered this node's heartbeats
        // with its index is of this version, so its 404 is not that (something else answered at that url)
        if (ret == null || (handover.offer && url == null && ret instanceof NodeHttpClient.CallException callException && callException.getStatusCode() == 404)) {
            if (handover.offer) {
                for (int nodeIndex : sent.sharedObjectSeq.keySet()) {
                    if (!declined.get().contains(nodeIndex))
                        handover.answered.add(nodeIndex);
                }
                handover.delivered = handover.answered.containsAll(handover.object.sharedObjectSeq.keySet());
                log.info("shared-object offered to node-index: {}{}", leaderIndex, ret == null ? "" : ", which takes no offers");
            } else {
                handover.delivered = true;
                log.info("split brain: shared-object synchronized to node-index: {}", leaderIndex);
            }
            if (declined.get().isEmpty()) {
                handoverBackoff.succeeded(handover.target);
            } else {
                // members of the leader, which gets their objects from themselves: offered again later, after the backoff,
                // since the leader may lose them before it has pulled their objects
                handoverBackoff.failed(handover.target, clusterStarter.heartbeatSendingIntervalMillis);
                log.info("shared-object of node-indexes: {} declined by node-index: {}, members there, offered again later", declined.get(), leaderIndex);
            }
            return;
        }
        // the next one after 1, 2, 4, 8 and then 16 heartbeat intervals, also for the next handover to that leader while
        // this node's heartbeats do not reach it
        int failures = handoverBackoff.failed(handover.target, clusterStarter.heartbeatSendingIntervalMillis);
        // sent again with a later heartbeat of the leader: no stack trace, and only the first failure in a row above debug.
        // A leader not found (its url unknown here: it does not hear this node yet) is not found again with each
        // demotion while a one-way link lasts: above debug only once per leader
        boolean warn = ret instanceof NoSuchElementException ? !handover.target.equals(unfoundLeader.getAndSet(handover.target)) : failures == 1;
        if (warn)
            log.warn("{} failed::{}", name, ret.getMessage());
        else
            log.debug("{} failed again ({} in a row)::{}", name, failures, ret.getMessage());
    }

    /**
     * split brain, once a handover call returned: answered, the winner has taken over what it carried (or holds it as
     * new, or knew it removed). One whose failover finished here after the demotion before the answer came (while the
     * call was on its way to a winner this node's heartbeats did not reach, see deliver) is held here again: see
     * {@link #completeRemoval}
     */
    private void handedOver(Handover handover, SharedObject sent, boolean answered) {
        List<Integer> heldAgain = new ArrayList<>();
        synchronized (replicaLock) {
            handover.sending = Set.of();
            if (answered) {
                handover.handedOver.addAll(sent.sharedObjectSeq.keySet());
                for (int nodeIndex : sent.sharedObjectSeq.keySet()) {
                    if (holdAgain(handover, nodeIndex))
                        heldAgain.add(nodeIndex);
                }
            }
        }
        for (int nodeIndex : heldAgain) {
            log.info("node-index: {}, whose failover finished after this node stopped leading, was taken over by the winner from this node's handover: this node holds it again", nodeIndex);
            ClusterEvents.fireEvents(clusterEvents.overwrittenEvents, nodeIndex, "overwritten (node-index: " + nodeIndex + ")");
        }
    }

    /**
     * under replicaLock, split brain: the winner took over the handed-over object of nodeIndex, whose failover finished
     * on a node that no longer led (this one, see {@link #softRemovals}), and this node holds no copy of it: it holds the
     * handed-over one again, the winner's copy, as it would once the winner listed it. Not for a member (it supplies its
     * own object), nor for an object known to be removed by a leader that led all the while
     * @return true when it holds it again
     */
    private boolean holdAgain(Handover handover, int nodeIndex) {
        var obj = handover.object.sharedObject.get(nodeIndex);
        var seq = handover.object.sharedObjectSeq.get(nodeIndex);
        var soft = softRemovals.get(nodeIndex);
        var removed = removedSeqs.get(nodeIndex);
        if (nodeIndex == clusterStarter.nodeIndex || obj == null || seq == null || soft == null || soft.seq() < seq ||
                (removed != null && removed >= seq) || sharedObject.containsKey(nodeIndex) ||
                pendingRemovals.containsKey(nodeIndex) || nodes.containsKey(nodeIndex))
            return false;
        sharedObject.put(nodeIndex, deepCopy(obj));
        sharedObjectSeq.put(nodeIndex, seq);
        return true;
    }

    /**
     * runs consumer against nodeIndex at url (see {@link #urlOf}) or, when it is null, at the url a lookup finds. The
     * lookup asks every node for its status, which a restarted node refuses until it has prepared, while it already
     * leads and sends heartbeats. Failures are left to the caller to log
     * @return null, or what failed ({@link NoSuchElementException} when the lookup found no url)
     */
    private Throwable toNode(int nodeIndex, String url, Consumer<String> consumer, String name) {
        var targetUrl = url != null ? url : redirectFunction.indexUrl(nodeIndex, name);
        if (targetUrl == null) {
            log.debug("({}) node-index: {} not found", name, nodeIndex);
            return new NoSuchElementException("node-index(" + nodeIndex + ") not found");
        }
        try {
            consumer.accept(targetUrl);
            return null;
        } catch (Throwable e) {
            log.debug("({}) node-index: {} failed (url={})::{}", name, nodeIndex, targetUrl, e.getMessage());
            return e;
        }
    }

    /**
     * the url this node reaches nodeIndex at, as the node answered this node's heartbeats there (one whose last heartbeat
     * got through first); null when none did (not yet, or a node of an older version)
     */
    private String urlOf(int nodeIndex) {
        String known = null;
        for (var entry : urlIndexes.entrySet()) {
            if (entry.getValue() != nodeIndex || !clusterStarter.nodeTargetUrls.contains(entry.getKey()))
                continue;
            if (reachableUrls.contains(entry.getKey()))
                return entry.getKey();
            known = entry.getKey();
        }
        return known;
    }

    /**
     * drops the replicas the leader does not hold, and fetches from it those whose sequence differs; replicas being handed
     * over to the leader are neither dropped nor replaced with an older copy meanwhile. A drop counts as a removal only
     * if this leader listed that replica before
     */
    private void syncWithLeader(LeaderId leader, Map<Integer, Long> leaderSeq, Map<Integer, Long> handingOver) {
        int leaderIndex = leader.index();
        // node-index -> sequence seen here when the fetch was decided (null: no replica)
        Map<Integer, Long> mismatched = new HashMap<>();
        synchronized (replicaLock) {
            for (var nodeIndex : new ArrayList<>(sharedObject.keySet())) {
                if (nodeIndex == clusterStarter.nodeIndex)
                    continue;
                var listed = leaderSeq.get(nodeIndex);
                var seq = sharedObjectSeq.get(nodeIndex);
                if (listed == null && !handingOver.containsKey(nodeIndex)) {
                    sharedObject.remove(nodeIndex);
                    sharedObjectSeq.remove(nodeIndex);
                    // a leader that never listed it (a new or restarted one, or a split-brain leader this node heard
                    // for a while) did not remove it: it may be offered that object later, and must not decline it
                    if (new Listing(leader, seq).equals(listings.remove(nodeIndex)))
                        recordDropped(nodeIndex, seq);
                    log.info("replica of node-index: {} dropped, the leader holds none", nodeIndex);
                } else if (listed != null && seq != null && listed >= seq) {
                    listings.put(nodeIndex, new Listing(leader, seq));
                }
            }
            for (var entry : leaderSeq.entrySet()) {
                var seq = sharedObjectSeq.get(entry.getKey());
                var handedOver = handingOver.get(entry.getKey());
                boolean older = handedOver != null && entry.getValue() != null && entry.getValue() < handedOver;
                if (entry.getKey() != clusterStarter.nodeIndex && !Objects.equals(entry.getValue(), seq) && !older) {
                    log.debug("heartbeat shared-object-sequence mismatch for node-index: {}, leader: {}, this: {}", entry.getKey(), entry.getValue(), seq);
                    mismatched.put(entry.getKey(), seq);
                } else {
                    log.trace("heartbeat shared-object-sequence match for node-index: {}", entry.getKey());
                }
            }
        }
        if (mismatched.isEmpty())
            return;
        var ret = redirectFunction.toIndexFunc(leaderIndex, targetUrl -> {
            for (var entry : mismatched.entrySet())
                fetchReplica(targetUrl, entry.getKey(), entry.getValue());
        }, "get shared-object");
        if (ret != null)
            log.error("get shared-object for sync follower from node-index: {} failed", leaderIndex, ret);
    }

    /** @return true when the replica was replaced or dropped */
    private boolean fetchReplica(String leaderUrl, int nodeIndex, Long observedSeq) {
        MergeSharedObjectInfo info;
        try {
            info = client.getSharedObject(leaderUrl, nodeIndex);
        } catch (NodeHttpClient.CallException e) {
            // only the route's own answer says that the leader holds none: not a 404 of something else at that url
            if (e.getStatusCode() != 404 || !ABSENT.equals(e.getReason())) {
                log.error("get shared-object for sync follower from node-index: {} failed::{}", nodeIndex, e.getMessage());
                return false;
            }
            info = null;
        }
        return applyFetched(nodeIndex, observedSeq, info);
    }

    /**
     * Applies the leader's replica of nodeIndex (null: the leader holds none) only if this node's replica still has the
     * sequence seen when the fetch was decided (compare-and-set): a change applied meanwhile is newer than that
     * decision, so the fetched copy is dropped and the next heartbeat decides again.
     * @return true when the replica was replaced or dropped
     */
    boolean applyFetched(int nodeIndex, Long observedSeq, MergeSharedObjectInfo info) {
        synchronized (replicaLock) {
            if (!Objects.equals(sharedObjectSeq.get(nodeIndex), observedSeq)) {
                log.debug("fetched shared-object of node-index: {} discarded, the replica changed meanwhile", nodeIndex);
                return false;
            }
            // an older leader answers null fields instead of 404
            if (info == null || info.obj == null) {
                sharedObject.remove(nodeIndex);
                listings.remove(nodeIndex);
                // the leader listed it (that is why it was fetched) and has removed it since
                recordDropped(nodeIndex, sharedObjectSeq.remove(nodeIndex));
                log.info("replica of node-index: {} dropped, the leader holds none", nodeIndex);
                return true;
            }
            sharedObject.put(nodeIndex, info.obj);
            sharedObjectSeq.put(nodeIndex, info.seq);
        }
        log.info("overwrite shared-object, sender-node-index: {}, shared-object-info: {}", nodeIndex, info);
        ClusterEvents.fireEvents(clusterEvents.overwrittenEvents, nodeIndex, "overwritten (node-index: " + nodeIndex + ")");
        return true;
    }

    private String overwriteLeaderSharedObject(int nodeIndex) {
        log.debug("overwrite leader shared-object for node-index: {}", nodeIndex);
        AtomicReference<MergeSharedObjectInfo> receivedSharedObjectInfo = new AtomicReference<>();
        var ret = redirectFunction.toIndexFunc(nodeIndex, targetUrl ->
                receivedSharedObjectInfo.set(client.getSharedObject(targetUrl)), "get shared-object");
        if (ret == null && receivedSharedObjectInfo.get() != null && receivedSharedObjectInfo.get().obj != null) {
            log.trace("get shared-object for sync leader from node-index: {} success", nodeIndex);
            overwriteSharedObject(nodeIndex, receivedSharedObjectInfo.get());
            return null;
        } else {
            log.error("get shared-object for sync leader from node-index: {} failed", nodeIndex, ret);
            return "get shared-object for sync leader from node-index: " + nodeIndex + " failed";
        }
    }

    private void clusterAdded(int nodeIndex) {
        if (disposed)
            return;
        log.info("cluster node added, nodeIndex: {}", nodeIndex);
        var added = new AtomicBoolean(false);
        nodes.computeIfAbsent(nodeIndex, key -> {
            added.set(true);
            return armNodeTimer(key);
        });
        // after the insert, so that the activation check counts the joining node
        if (added.get()) {
            removalsRequested.remove(nodeIndex);
            ClusterEvents.fireEvents(clusterEvents.clusterAddedEvents, nodeIndex, "cluster(node-index: " + nodeIndex + ") added");
            verifyActivation();
        }
    }

    /** this node's timer for nodeIndex expired: its own membership view changes, while the leader decides about the node's data */
    void clusterDeleted(int nodeIndex) {
        Disposable removed = nodes.remove(nodeIndex);
        if (removed != null) {
            removed.dispose();
            log.info("cluster node removed, nodeIndex: {}", nodeIndex);
            // before remove-shared-object, whose cluster-deleted events (failover) must see the updated activation
            verifyActivation();
            if (clusterStarter.position == Position.LEADER) {
                removeSharedObject(nodeIndex);
                return;
            }
            try {
                redirectFunction.toLeaderFuncConfirmed(targetUrl ->
                                client.removeSharedObject(targetUrl, nodeIndex),
                        "remove shared-object");
            } catch (RuntimeException e) {
                log.error("remove shared-object for node-index: {} failed", nodeIndex, e);
            }
        } else {
            log.trace("node-index: {}, already deleted", nodeIndex);
        }
    }

    /** see {@link #replicaDeleted(int, Long, boolean, int)}, from a sender not known */
    void replicaDeleted(int nodeIndex, Long seq, boolean demoted) {
        replicaDeleted(nodeIndex, seq, demoted, 0);
    }

    /**
     * the leader's cluster-deleted broadcast: the node's failover has finished, so its data goes. Membership is left to
     * this node's own timers
     * @param seq the sequence of the object failed over (null from a leader of an older version)
     * @param demoted the failover finished after the sender stopped leading (see {@link #completeRemoval}): a soft removal
     *                (see {@link #softRemovals})
     * @param sender demoted: the node whose failover it was (0: not known)
     */
    void replicaDeleted(int nodeIndex, Long seq, boolean demoted, int sender) {
        // the leader decides about the data itself: a late broadcast of a demoted leader must not drop its copy of a live
        // member. It remembers what was failed over, so that it does not take that object over again from an offer or a
        // handover. A copy it took over from one of those meanwhile (a node that is not a member, no newer than that),
        // failed over already, would be failed over again: it drops that one, unless the failover finished after its
        // leader was demoted. Such a failover may have placed nothing, finding what it placed through this node
        // registered here with that copy: this node fails it over itself (see completeRemoval). It declines that object
        // at that sequence only while it leads (see declinedSeq)
        if (clusterStarter.position == Position.LEADER) {
            boolean dropped = false;
            synchronized (replicaLock) {
                if (demoted)
                    recordSoftRemoved(nodeIndex, seq);
                else
                    recordRemoved(nodeIndex, seq);
                var held = sharedObjectSeq.get(nodeIndex);
                if (!demoted && seq != null && held != null && held <= seq && !nodes.containsKey(nodeIndex)) {
                    sharedObject.remove(nodeIndex);
                    sharedObjectSeq.remove(nodeIndex);
                    dropped = true;
                }
            }
            if (dropped)
                log.info("cluster deleted for node-index: {} (shared-object-sequence: {}), its replica is dropped, it was failed over already", nodeIndex, seq);
            else if (demoted)
                log.info("cluster deleted for node-index: {} (shared-object-sequence: {}) after its leader was demoted, remembered: this node, the leader, keeps what it holds of it", nodeIndex, seq);
            else
                log.info("cluster deleted for node-index: {} (shared-object-sequence: {}) ignored, this node is the leader", nodeIndex, seq);
            return;
        }
        var handover = pendingHandover.get();
        var handedOver = handover == null ? null : handover.held().get(nodeIndex);
        boolean removed;
        boolean kept = false;
        synchronized (replicaLock) {
            // a newer copy than the one failed over is kept while it is handed over to the leader, which then fails it over too
            if (handedOver != null && (seq == null || seq < handedOver)) {
                log.info("cluster deleted for node-index: {} (shared-object-sequence: {}), its replica is kept until the newer one handed over is delivered", nodeIndex, seq);
                if (demoted)
                    recordSoftRemoved(nodeIndex, seq);
                else
                    recordRemoved(nodeIndex, seq);
                return;
            }
            if (demoted && seq != null && sender != 0) {
                // a copy of that object that the demoted node did not list here came from the leader that took that object
                // over (pushed by it, or fetched from it), which holds it while its own failover waits for the absence
                // grace: the demoted node's failover may have placed nothing, finding what it placed through that leader
                // registered there. Kept, like any copy the leader lists: should that leader die first, the next leader
                // holds it or is offered it, and fails it over. A copy the demoted node listed (this node followed it) is
                // dropped: no other leader is known to hold that object (had the winner taken it over from the demoted
                // node's handover, that node would hold it again and not broadcast), so the failover could place it, and
                // it is offered to no leader, which may not have learned of that failover yet (a one-way link). Without
                // the sender (a development build), every copy is dropped
                var listing = listings.get(nodeIndex);
                var held = sharedObjectSeq.get(nodeIndex);
                kept = held != null && held >= seq && (listing == null || listing.leader().index() != sender);
            }
            if (kept) {
                removed = false;
                recordSoftRemoved(nodeIndex, seq);
            } else {
                removed = sharedObject.remove(nodeIndex) != null;
                listings.remove(nodeIndex);
                var dropped = sharedObjectSeq.remove(nodeIndex);
                // once elected, this node does not take that object over again (nor the copy dropped, from an older leader),
                // unless the failover finished after its leader was demoted
                if (demoted)
                    recordSoftRemoved(nodeIndex, seq != null ? seq : dropped);
                else
                    recordRemoved(nodeIndex, seq != null ? seq : dropped);
            }
        }
        if (kept)
            log.info("cluster deleted for node-index: {} (shared-object-sequence: {}) after its leader was demoted: its replica is kept, node-index: {} did not list it here", nodeIndex, seq, sender);
        else
            log.debug("node-index: {}, replica {}", nodeIndex, removed ? "removed" : "already removed");
    }

    /** under replicaLock: the object of nodeIndex at seq (null: none) or older is known to be removed */
    private void recordRemoved(int nodeIndex, Long seq) {
        if (seq != null)
            removedSeqs.merge(nodeIndex, seq, Math::max);
    }

    /**
     * under replicaLock: the failover of the object of nodeIndex at seq (null: none) or older finished on a node that no
     * longer led (see {@link #softRemovals}); learned while this node leads, it declines that object while it leads
     */
    private void recordSoftRemoved(int nodeIndex, Long seq) {
        if (seq == null)
            return;
        var leading = clusterStarter.position == Position.LEADER ? lastTransitionTime : null;
        softRemovals.merge(nodeIndex, new SoftRemoval(seq, leading), (known, learned) -> learned.seq() >= known.seq() ? learned : known);
    }

    /** under replicaLock: the newest sequence of nodeIndex known to be removed, softly or not (null: none) */
    private Long removedSeq(int nodeIndex) {
        var removed = removedSeqs.get(nodeIndex);
        var soft = softRemovals.get(nodeIndex);
        return soft == null || (removed != null && removed >= soft.seq()) ? removed : Long.valueOf(soft.seq());
    }

    /**
     * under replicaLock: the object of nodeIndex at seq is known to be removed here, so it is neither handed over nor
     * offered any more: failed over by a leader that led all the while, or softly (see {@link #softRemovals}) with no copy
     * of that object (or a newer one) held here since. A copy held after such a failover came from the leader that took
     * that object over (kept on the broadcast, fetched again, or the copy this node handed over, held again): that leader
     * holds it, and a later leader is offered it if it lacks it
     */
    private boolean removedHere(int nodeIndex, long seq) {
        var removed = removedSeqs.get(nodeIndex);
        if (removed != null && removed >= seq)
            return true;
        var soft = softRemovals.get(nodeIndex);
        if (soft == null || soft.seq() < seq)
            return false;
        var held = sharedObjectSeq.get(nodeIndex);
        var pending = pendingRemovals.get(nodeIndex);
        return (held == null || held < soft.seq()) && (pending == null || pending.seq < soft.seq());
    }

    /**
     * under replicaLock: the newest sequence of nodeIndex that this node, as leader, declines to take over (null: none):
     * known to be removed, or failed over softly and learned while this node leads (the leader it was left to). A soft
     * removal learned otherwise is not proof that the devices were placed: a node that leads later takes that object over
     * and fails it over
     */
    private Long declinedSeq(int nodeIndex) {
        var removed = removedSeqs.get(nodeIndex);
        var soft = softRemovals.get(nodeIndex);
        if (soft == null || soft.leading() == null || soft.leading() != lastTransitionTime || clusterStarter.position != Position.LEADER)
            return removed;
        return removed != null && removed >= soft.seq() ? removed : Long.valueOf(soft.seq());
    }

    /**
     * under replicaLock: a copy dropped because the leader that held it holds it no longer. The leader decided about it,
     * so once elected this node does not take it over again; not for a node this node still hears, whose object the
     * leader may not have pulled yet
     */
    private void recordDropped(int nodeIndex, Long seq) {
        if (!nodes.containsKey(nodeIndex))
            recordRemoved(nodeIndex, seq);
    }

    /**
     * The leader removes a lost node's data: it is taken out of the registration view, the cluster-deleted events (failover)
     * run with it, and only then do the followers drop their copy - until that, a newly elected leader still holds it.
     * Requested by this node's timer or a follower's (see {@link #removalsRequested})
     */
    void removeSharedObject(int nodeIndex) {
        removeSharedObject(nodeIndex, true);
    }

    /** @param requested by a node's timer, not by this node's removal of absent replicas */
    private void removeSharedObject(int nodeIndex, boolean requested) {
        if (nodeIndex == clusterStarter.nodeIndex)
            return;
        if (disposed) {
            log.info("remove shared-object for node-index: {} ignored, disposed", nodeIndex);
            return;
        }
        // demoted since the sender looked it up: the leader's own timer, or its removal of absent replicas, covers it
        if (clusterStarter.position != Position.LEADER) {
            log.info("remove shared-object for node-index: {} ignored, this node is not the leader", nodeIndex);
            return;
        }
        // a follower's timer also expires on a one-way link loss or when the follower itself stalled
        if (nodes.containsKey(nodeIndex)) {
            log.info("remove shared-object for node-index: {} ignored, its heartbeats are still received", nodeIndex);
            return;
        }
        if (requested)
            removalsRequested.put(nodeIndex, System.nanoTime());
        // this node's leadership the failover runs under: see completeRemoval
        var leading = lastTransitionTime;
        MergeSharedObjectInfo removed;
        synchronized (replicaLock) {
            var obj = sharedObject.remove(nodeIndex);
            var seq = sharedObjectSeq.remove(nodeIndex);
            if (obj == null) {
                log.trace("node-index: {}, shared-object already removed", nodeIndex);
                return;
            }
            removed = new MergeSharedObjectInfo(seq == null ? 0L : seq, obj);
            pendingRemovals.put(nodeIndex, removed);
        }
        log.debug("node-index: {}, removed shared-object process", nodeIndex);
        removalExecutor.execute(() -> completeRemoval(nodeIndex, removed, leading));
    }

    /**
     * runs the failover and then broadcasts it: marked as such if this node did not lead all the while. A node demoted
     * meanwhile handed the object over to the winner, which fails it over itself once the node has been absent for the
     * grace (see {@link #removeAbsentReplicas}). A failover that places the devices through the leader finds them
     * registered there while the winner holds the object, so on the demoted node it may have placed nothing: it counts
     * as a soft removal (see {@link #softRemovals}), here too. Once the winner has taken that object over from this
     * node's handover (it answered a call carrying it, or one is on its way to a winner this node's heartbeats reach),
     * this node holds the copy it handed over again, the winner's, and does not broadcast: should the winner die before
     * its own failover, the leader after it holds that object or is offered it, or this node fails it over if it leads
     * next, and a follower keeps what it holds of it meanwhile, also a copy that it fetched from this node. Otherwise
     * (the handover did not get through: a one-way link) it is broadcast marked as such. A leader keeps what it holds of
     * that object on the marked broadcast, where an unmarked one would make the winner drop it, and the devices would
     * run nowhere, and it takes that object over again from no offer or handover while it leads. A follower keeps a copy
     * that this node did not list there, the winner's (should the winner die before its own failover, the next leader
     * holds it or is offered it), and drops one that this node listed, which it offers no leader
     * @param leading this node's last transition time when the removal began
     */
    private void completeRemoval(int nodeIndex, MergeSharedObjectInfo removed, ZonedDateTime leading) {
        Map<String, Object> object;
        synchronized (replicaLock) {
            object = deepCopy(removed.obj);
        }
        try {
            ClusterEvents.fireEventsAndWait(clusterEvents.clusterDeletedEvents, nodeIndex, object, "cluster(node-index: " + nodeIndex + ") deleted, object: (" + object + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        boolean finished;
        boolean broadcast;
        boolean heldAgain = false;
        boolean led = clusterStarter.position == Position.LEADER && lastTransitionTime == leading;
        var handover = pendingHandover.get();
        synchronized (replicaLock) {
            finished = pendingRemovals.remove(nodeIndex, removed);
            // after the demotion, not proof that the devices were placed: a copy held again here (the winner's) is still
            // offered to the leader after the winner, should the winner die before its own failover
            if (finished && led) {
                recordRemoved(nodeIndex, removed.seq);
            } else if (finished) {
                recordSoftRemoved(nodeIndex, removed.seq);
                // the winner took that object over from this node's handover, or may do so any moment (a call carrying it
                // is on its way to a winner this node reaches): what this failover placed through the winner was refused
                // meanwhile. This node holds the handed-over copy again, as it would once the winner listed it
                heldAgain = handover != null && !handover.offer &&
                        (handover.handedOver.contains(nodeIndex) || handover.sending.contains(nodeIndex)) && holdAgain(handover, nodeIndex);
            }
            // not when disposed or superseded meanwhile, nor when the node came back or this node holds a copy again
            // (the leader then holds it again: demoted meanwhile, this node fetched the winner's copy, or the winner took
            // over the copy handed over)
            broadcast = finished && !sharedObject.containsKey(nodeIndex) && !nodes.containsKey(nodeIndex);
        }
        if (finished && !led)
            log.info("node-index: {}, failover finished after this node stopped leading, {}", nodeIndex,
                    heldAgain ? "not broadcast: the winner took it over from this node's handover, and this node holds it again (the winner fails it over)"
                            : broadcast ? "broadcast as such: the leader keeps (and fails over) what it holds of it" : "not broadcast: the node is back, or the leader holds it again");
        if (heldAgain)
            ClusterEvents.fireEvents(clusterEvents.overwrittenEvents, nodeIndex, "overwritten (node-index: " + nodeIndex + ")");
        if (broadcast) {
            log.debug("node-index: {}, failover finished, broadcast cluster deleted", nodeIndex);
            // fire-and-forget: the lost node itself may accept the connection and never answer. With the sequence, so that
            // followers decline offers of that object later, and with this node's index if marked: a follower keeps no copy
            // that this node listed there
            removalExecutor.execute(() ->
                    redirectFunction.toAllFunc(targetUrl -> client.clusterDeleted(targetUrl, nodeIndex, removed.seq, !led, clusterStarter.nodeIndex), "cluster deleted"));
        }
    }

    /**
     * leader: removes held replicas of nodes that are not members once they have been absent for longer than removing a
     * lost node takes. This reruns a failover that a previous leader did not finish, and clears entries no timer removes.
     */
    private void removeAbsentReplicas() {
        if (recentlyStalled())
            return;
        long now = System.nanoTime();
        long graceNanos = absenceGraceNanos();
        List<Integer> held;
        synchronized (replicaLock) {
            held = new ArrayList<>(sharedObject.keySet());
        }
        absentSince.keySet().retainAll(held);
        for (int nodeIndex : held) {
            // also one taken over from a demoted leader whose failover of it still ran there, which leaves it to this
            // node (a second run of a failover finds what the first one placed registered already)
            if (nodeIndex == clusterStarter.nodeIndex || nodes.containsKey(nodeIndex)) {
                absentSince.remove(nodeIndex);
                continue;
            }
            long since = absentSince.computeIfAbsent(nodeIndex, key -> now);
            if (now - since > graceNanos) {
                absentSince.remove(nodeIndex);
                log.info("replica of node-index: {} held, but the node is not a member, remove it", nodeIndex);
                removeSharedObject(nodeIndex, false);
            }
        }
    }

    /** how long a held replica's node is not a member before the leader removes it: longer than removing a lost node takes */
    private long absenceGraceNanos() {
        return TimeUnit.SECONDS.toNanos(clusterStarter.leaderLostTimeoutSeconds) +
                TimeUnit.MILLISECONDS.toNanos(clusterStarter.heartbeatSendingIntervalMillis);
    }

    private synchronized void verifyActivation() {
        if (nodes.size() > maxClusterSize)
            maxClusterSize = nodes.size();
        int quorum = clusterStarter.quorum;
        if (quorum <= 0)
            quorum = maxClusterSize/2 + 1;
        log.trace("current quorum: {}", quorum);

        if (nodes.size() < quorum && clusterStarter.isActivated) {
            log.info("application inactivated");
            clusterStarter.isActivated = false;
            ClusterEvents.fireEvents(clusterEvents.inactivatedEvents, "inactivated");
        }
        else if (nodes.size() >= quorum && !clusterStarter.isActivated) {
            log.info("application activated");
            clusterStarter.isActivated = true;
            ClusterEvents.fireEvents(clusterEvents.activatedEvents, "activated");
        }
    }

    String setSharedObjectToLeader(int senderNodeIndex, SharedObjectInfo sharedObjectInfo) {
        log.debug("set shared-object to leader, sender-node-index: {}, shared-object-info: {}", senderNodeIndex, sharedObjectInfo);
        synchronized (syncMutex) {
            if (senderNodeIndex != clusterStarter.nodeIndex) {
                if (applyDelta(senderNodeIndex, sharedObjectInfo)) {
                    log.trace("set shared-object to leader, shared-object-sequence match for node-index: {}", senderNodeIndex);
                } else if (containsChange(senderNodeIndex, sharedObjectInfo)) {
                    // pulled (heartbeat sequence check) while the change was on its way: not pulled again for each change
                    // that follows, and still propagated, for the followers that only have the changes before it
                    log.trace("set shared-object to leader, shared-object-sequence: {} of node-index: {} already applied", seqOf(sharedObjectInfo), senderNodeIndex);
                } else {
                    // also without a replica of the sender (it joined, or was removed meanwhile): its object is pulled,
                    // never an empty placeholder that the delta would be applied onto
                    log.trace("set shared-object to leader, shared-object-sequence mismatch for node-index: {}, leader: {}, sender: {}", senderNodeIndex, replicaSeq(senderNodeIndex), seqOf(sharedObjectInfo));
                    var ret = overwriteLeaderSharedObject(senderNodeIndex);
                    if (ret != null) return ret;
                }
            }

            // the fan-out does not wait here: a peer that does not answer would outlast the sender's call, which then
            // retries forever. Queued under syncMutex so propagations run in sequence order; a follower that misses
            // one is healed by the heartbeat sequence check
            queuePropagation(senderNodeIndex, sharedObjectInfo);
        }
        return null;
    }

    /**
     * Queues the change for each peer in its own lane (per sender and peer), which sends everything queued meanwhile as
     * one batch, in sequence order, once its previous batch was answered: a follower applies each change instead of being
     * overwritten with the whole replica, and neither a slow peer nor another sender's changes hold up a lane. Only a
     * backlog beyond MAX_PENDING_PROPAGATIONS is replaced by one copy of the whole replica.
     * @return false when no peer (other than the sender) is reachable: nothing is queued then, and there is nothing to wait for
     */
    private boolean queuePropagation(int senderNodeIndex, SharedObjectInfo sharedObjectInfo) {
        // only peers that answered their last call: a node that is down, or a URL of an interface not reachable from
        // here, would hold up the leader's own changes waiting on it for a connect timeout. A peer left out is
        // resynchronized by the heartbeat sequence check (or its lane's next batch, which then finds it behind)
        var targetUrls = new ArrayList<>(clusterStarter.nodeTargetUrls);
        targetUrls.retainAll(reachableUrls);
        // nor back to a follower that sent the change: it has it (and would answer the batch without doing anything)
        targetUrls.removeIf(targetUrl -> Objects.equals(urlIndexes.get(targetUrl), senderNodeIndex));
        if (targetUrls.isEmpty())
            return false;
        long seq = seqOf(sharedObjectInfo);
        List<PropagationLane> idle = new ArrayList<>();
        boolean overflow = false;
        synchronized (propagationLanes) {
            var lanes = propagationLanes.computeIfAbsent(senderNodeIndex, key -> new HashMap<>());
            for (var targetUrl : targetUrls) {
                var lane = lanes.computeIfAbsent(targetUrl, key -> new PropagationLane(senderNodeIndex, key));
                lane.queuedSeq = Math.max(lane.queuedSeq, seq);
                if (!lane.wholeReplica) {
                    if (lane.changes.size() < MAX_PENDING_PROPAGATIONS) {
                        lane.changes.add(sharedObjectInfo);
                    } else {
                        lane.changes.clear();
                        lane.wholeReplica = true;
                        overflow = true;
                    }
                }
                if (!lane.draining) {
                    lane.draining = true;
                    idle.add(lane);
                }
            }
        }
        if (overflow)
            log.debug("more than {} shared-object changes of node-index: {} wait for a peer, the whole replica is sent instead", MAX_PENDING_PROPAGATIONS, senderNodeIndex);
        for (var lane : idle)
            propagationExecutor.execute(() -> drainLane(lane));
        return true;
    }

    /** sends the lane's batches, one after the other, until it is empty */
    private void drainLane(PropagationLane lane) {
        boolean draining = true;
        try {
            while (draining)
                draining = sendNextBatch(lane);
        } finally {
            // stopped by an unexpected error: the lane's next change starts it again
            if (draining) {
                synchronized (propagationLanes) {
                    lane.draining = false;
                    propagationLanes.notifyAll();
                }
            }
        }
    }

    /** @return false when the lane was empty (and is idle now) */
    private boolean sendNextBatch(PropagationLane lane) {
        List<SharedObjectInfo> changes;
        boolean wholeReplica;
        long newestSeq;
        synchronized (propagationLanes) {
            if (disposed || (lane.changes.isEmpty() && !lane.wholeReplica)) {
                lane.changes.clear();
                lane.wholeReplica = false;
                lane.draining = false;
                propagationLanes.notifyAll();
                return false;
            }
            changes = new ArrayList<>(lane.changes);
            wholeReplica = lane.wholeReplica;
            newestSeq = lane.queuedSeq;
            lane.changes.clear();
            lane.wholeReplica = false;
        }
        boolean inBudget = false;
        try {
            inBudget = sendBatch(lane.url, lane.senderNodeIndex, changes, wholeReplica);
        } finally {
            synchronized (propagationLanes) {
                lane.doneSeq = Math.max(lane.doneSeq, newestSeq);
                // until a batch is answered within the budget again, the leader's own changes do not wait for this peer
                lane.lagging = !inBudget;
                propagationLanes.notifyAll();
            }
        }
        return true;
    }

    /**
     * one batch of a sender's changes to one peer (or its whole replica): the peer applies them in order and skips those
     * it has already; a peer that cannot (it is further behind) gets the whole replica
     * @return true when the peer answered all of it within the budget (the probe timeout)
     */
    private boolean sendBatch(String targetUrl, int senderNodeIndex, List<SharedObjectInfo> changes, boolean wholeReplica) {
        long begin = System.nanoTime();
        long budgetNanos = client.probeTimeout().toNanos();
        try {
            log.trace("propagate {} shared-object change(s) for node-index: {} (url={})", wholeReplica ? "all" : changes.size(), senderNodeIndex, targetUrl);
            boolean complete = true;
            if (wholeReplica)
                overwriteTarget(targetUrl, senderNodeIndex);
            else
                complete = sendChanges(targetUrl, senderNodeIndex, changes, begin, budgetNanos);
            return complete && System.nanoTime() - begin <= budgetNanos;
        } catch (RuntimeException e) {
            reachableUrls.remove(targetUrl);
            log.error("propagate shared-object for node-index: {} failed (url={})::{}", senderNodeIndex, targetUrl, e.getMessage());
            return false;
        }
    }

    /** @return false when a peer of an older version, sent one change per call, was cut off by the budget */
    private boolean sendChanges(String targetUrl, int senderNodeIndex, List<SharedObjectInfo> changes, long begin, long budgetNanos) {
        if (!sendsPerChange(targetUrl)) {
            try {
                var result = client.checkSharedObjectChanges(targetUrl, senderNodeIndex, SharedObjectChange.of(changes));
                if (result == null || !result.applied) {
                    log.trace("peer (url={}) holds shared-object-sequence: {} of node-index: {}, send the whole replica", targetUrl, result == null ? null : result.seq, senderNodeIndex);
                    overwriteTarget(targetUrl, senderNodeIndex);
                }
                return true;
            } catch (NodeHttpClient.CallException e) {
                if (e.getStatusCode() != 404)
                    throw e;
                perChangeUrls.put(targetUrl, System.nanoTime());
                log.info("peer (url={}) takes no batches of shared-object changes (older version), send them one by one", targetUrl);
            }
        }
        // in sequence order, each after the previous one was answered. A peer that is not exactly one change behind gets
        // the whole replica once, instead of the rest; one too slow for all of them within the budget gets the rest with
        // the next batch (which then finds it behind) or from the heartbeat sequence check
        for (var change : changes) {
            boolean applied = change instanceof MergeSharedObjectInfo mergeSharedObjectInfo
                    ? client.checkMergeSharedObject(targetUrl, senderNodeIndex, mergeSharedObjectInfo)
                    : client.checkDeleteSharedObject(targetUrl, senderNodeIndex, (DeleteSharedObjectInfo) change);
            if (!applied) {
                overwriteTarget(targetUrl, senderNodeIndex);
                return true;
            }
            if (System.nanoTime() - begin > budgetNanos) {
                log.debug("propagate shared-object for node-index: {} to a slow peer interrupted (url={})", senderNodeIndex, targetUrl);
                return false;
            }
        }
        return true;
    }

    /** a peer of an older version, until the batch route is tried again */
    private boolean sendsPerChange(String targetUrl) {
        var since = perChangeUrls.get(targetUrl);
        if (since == null)
            return false;
        if (System.nanoTime() - since < PER_CHANGE_RECHECK_NANOS)
            return true;
        perChangeUrls.remove(targetUrl, since);
        return false;
    }

    /** the whole replica, copied when sent: it holds every change queued before */
    private void overwriteTarget(String targetUrl, int senderNodeIndex) {
        var current = copyReplica(senderNodeIndex, false);
        if (current != null)
            client.overwriteSharedObject(targetUrl, senderNodeIndex, current);
    }

    /**
     * returns once every peer that keeps up has been sent the change with seq, or after the probe timeout. A lagging peer
     * (its last batch took longer than the budget, or failed) is not waited for: it still gets every batch, and is waited
     * for again once one is answered within the budget
     */
    private void awaitPropagated(int senderNodeIndex, long seq) {
        long deadline = System.nanoTime() + client.probeTimeout().toNanos();
        synchronized (propagationLanes) {
            while (propagating(senderNodeIndex, seq)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    break;
                try {
                    TimeUnit.NANOSECONDS.timedWait(propagationLanes, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (System.nanoTime() - deadline >= 0)
            log.debug("shared-object-sequence: {} not propagated within the probe timeout, continue", seq);
    }

    /** under propagationLanes: a peer that keeps up has the change with seq queued, but not sent yet */
    private boolean propagating(int senderNodeIndex, long seq) {
        var lanes = propagationLanes.get(senderNodeIndex);
        if (lanes == null)
            return false;
        for (var lane : lanes.values()) {
            if (!lane.lagging && lane.queuedSeq >= seq && lane.doneSeq < seq)
                return true;
        }
        return false;
    }

    /** split-brain winner, for a handover without an id (older version): see {@link #syncSharedObject(int, SharedObject, String, Map)} */
    void syncSharedObject(SharedObject object) {
        syncSharedObject(0, object, null, Map.of());
    }

    /**
     * split-brain winner: takes over what the demoted leader holds newer and returns, so the demoted node gets its answer
     * whatever the other peers do. Bringing the followers in line (each peer checked with the probe timeout) and the
     * split-brain-resolved event follow on another thread, once per handover: the demoted node sends a handover again
     * when it did not get the answer (the last time without the objects, when this node's heartbeat listed them already).
     * The objects of nodes that are not members are failed over here once they have been absent for the grace, also
     * those whose failover still ran on the demoted node: that node leaves them to this one
     * @param removed node-index -> sequence of the handed-over objects failed over on the demoted node since, sent
     *                instead of them: remembered as that node's marked broadcast of their failover would be (a soft
     *                removal, which this node declines those objects for while it leads), before anything is taken over
     */
    void syncSharedObject(int senderNodeIndex, SharedObject object, String handoverId, Map<Integer, Long> removed) {
        log.warn("synchronize split brain nodes start");
        for (var entry : removed.entrySet()) {
            if (entry.getKey() != clusterStarter.nodeIndex)
                replicaDeleted(entry.getKey(), entry.getValue(), true, senderNodeIndex);
        }
        var adopted = adopt(object, false, null);
        for (var nodeIndex : adopted) {
            log.info("split brain: shared-object of node-index: {} adopted from the demoted leader", nodeIndex);
            ClusterEvents.fireEvents(clusterEvents.overwrittenEvents, nodeIndex, "overwritten (node-index: " + nodeIndex + ")");
        }
        if (handoverId != null && handoverId.equals(handledHandovers.put(senderNodeIndex, handoverId))) {
            log.info("split brain: handover of node-index: {} received again, resolved already", senderNodeIndex);
            return;
        }
        Schedulers.io().scheduleDirect(this::resolveSplitBrain);
    }

    private void resolveSplitBrain() {
        if (disposed)
            return;
        try {
            var sequences = heartbeatSequences();
            var results = new ConcurrentHashMap<String, Set<Integer>>();
            redirectFunction.toAllFunc(targetUrl ->
                            results.put(targetUrl, client.checkSharedObjectSeq(targetUrl, sequences)),
                    "check shared-object-sequence");
            var syncList = results.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream().map(nodeIndex -> new Pair<>(entry.getKey(), nodeIndex)))
                    .collect(Collectors.toList());
            redirectFunction.parallelExecute(syncList, sync -> {
                var current = copyReplica(sync.getValue1(), true);
                if (current != null)
                    client.overwriteSharedObject(sync.getValue0(), sync.getValue1(), current);
            });
        } catch (RuntimeException e) {
            // the heartbeat sequence check brings them in line later
            log.error("synchronize split brain nodes failed::{}", e.getMessage());
        }
        ClusterEvents.fireEvents(clusterEvents.splitBrainResolvedEvents, "split brain resolved");
        log.trace("synchronize split brain nodes end");
    }

    /**
     * leader: takes over the offered replicas of nodes that are not members, each only if newer than what it holds or
     * knows to be removed (see {@link #declinedSeq}). Such a replica is then failed over like any held replica of a node
     * that is gone, or right away if the node's removal was requested within the absence grace before (see
     * {@link #removalsRequested}) and no other leader was heard meanwhile
     * @return the node-indexes declined because the node is a member and this node holds no replica as new (the follower
     *         offers it again later: the member may be gone before its own object was pulled), null when this node is
     *         not the leader
     */
    Set<Integer> adoptOffered(int senderNodeIndex, SharedObject object) {
        if (disposed || clusterStarter.position != Position.LEADER)
            return null;
        Set<Integer> declined = new HashSet<>();
        for (var nodeIndex : adopt(object, true, declined)) {
            log.info("shared-object of node-index: {} offered by node-index: {} taken over, the node is not a member", nodeIndex, senderNodeIndex);
            ClusterEvents.fireEvents(clusterEvents.overwrittenEvents, nodeIndex, "overwritten (node-index: " + nodeIndex + ")");
            // its removal was requested lately (this node's timer, or a follower's that expired before the offer came):
            // removed now, as that request would have removed it had the copy been held then. Not while another leader
            // may still be failing that node over (see otherLeaderHeard)
            var requested = removalsRequested.get(nodeIndex);
            var otherLeader = otherLeaderHeard;
            long now = System.nanoTime();
            if (requested != null && now - requested <= absenceGraceNanos() && (otherLeader == null || now - otherLeader > absenceGraceNanos())) {
                log.info("node-index: {}, whose removal was requested lately, is removed now", nodeIndex);
                removeSharedObject(nodeIndex, false);
            }
        }
        if (!declined.isEmpty())
            log.debug("shared-object of node-indexes: {} offered by node-index: {} declined, the nodes are members", declined, senderNodeIndex);
        return declined;
    }

    /**
     * takes over each replica of object that is newer than the one held here, or, if none is, than the one whose removal
     * (failover) is running or finished here: a copy of an object failed over already is not taken over again
     * @param absentOnly only replicas of nodes that are not members (whose own object is not coming from themselves)
     * @param declined absentOnly: collects the members whose replica would otherwise have been taken over
     * @return the node-indexes taken over
     */
    private List<Integer> adopt(SharedObject object, boolean absentOnly, Set<Integer> declined) {
        List<Integer> adopted = new ArrayList<>();
        if (object == null || object.sharedObject == null || object.sharedObjectSeq == null)
            return adopted;
        synchronized (replicaLock) {
            for (var entry : object.sharedObject.entrySet()) {
                int nodeIndex = entry.getKey();
                var seq = object.sharedObjectSeq.get(nodeIndex);
                if (nodeIndex == clusterStarter.nodeIndex || entry.getValue() == null || seq == null)
                    continue;
                var current = sharedObjectSeq.get(nodeIndex);
                if (absentOnly && nodes.containsKey(nodeIndex)) {
                    if (declined != null && (current == null || current < seq))
                        declined.add(nodeIndex);
                    continue;
                }
                if (current == null && pendingRemovals.containsKey(nodeIndex))
                    current = pendingRemovals.get(nodeIndex).seq;
                // failed over already: only a newer object than the one handed to the failover is taken over, not
                // the older copy of a demoted leader that could hand its copies over only later (a failover that finished
                // on a node no longer leading counts only if learned while this node leads: see declinedSeq)
                if (current == null)
                    current = declinedSeq(nodeIndex);
                // the owner may have changed its object on the demoted leader's side only, or be gone by now
                if (current == null || seq > current) {
                    sharedObject.put(nodeIndex, entry.getValue());
                    sharedObjectSeq.put(nodeIndex, seq);
                    adopted.add(nodeIndex);
                } else if (absentOnly) {
                    log.debug("offered shared-object of node-index: {} (shared-object-sequence: {}) not taken over, held or known to be removed at: {}", nodeIndex, seq, current);
                }
            }
        }
        return adopted;
    }

    /** replaces the replica: object and sequence together */
    void overwriteSharedObject(int nodeIndex, MergeSharedObjectInfo sharedObjectInfo) {
        log.info("overwrite shared-object, sender-node-index: {}, shared-object-info: {}", nodeIndex, sharedObjectInfo);
        if (sharedObjectInfo.obj == null) {
            log.warn("overwrite shared-object for node-index: {} ignored, no object", nodeIndex);
            return;
        }
        synchronized (replicaLock) {
            sharedObject.put(nodeIndex, sharedObjectInfo.obj);
            sharedObjectSeq.put(nodeIndex, sharedObjectInfo.seq);
        }
        ClusterEvents.fireEvents(clusterEvents.overwrittenEvents, nodeIndex, "overwritten (node-index: " + nodeIndex + ")");
    }

    /**
     * false when this node holds no replica of the sender or is behind it by more than this change: the leader then
     * overwrites it. A replica that already has the change is left as it is
     */
    boolean checkSharedObject(int senderNodeIndex, SharedObjectInfo sharedObjectInfo) {
        log.debug("check shared-object, sender-node-index: {}, shared-object-info: {}", senderNodeIndex, sharedObjectInfo);
        synchronized (replicaLock) {
            if (applyDelta(senderNodeIndex, sharedObjectInfo)) {
                log.trace("received shared-object-sequence match for node-index: {}", senderNodeIndex);
                return true;
            }
            // a whole replica (the leader's overwrite, or a fetch after a heartbeat) got ahead of the changes still on
            // their way: refusing them would make the leader send the whole replica again, and again under steady writes
            if (containsChange(senderNodeIndex, sharedObjectInfo)) {
                log.trace("received shared-object-sequence: {} for node-index: {} already applied", seqOf(sharedObjectInfo), senderNodeIndex);
                return true;
            }
        }
        log.trace("checkSharedObject shared-object-sequence mismatch for node-index: {}, leader: {}, this: {}", senderNodeIndex, seqOf(sharedObjectInfo), replicaSeq(senderNodeIndex));
        return false;
    }

    /**
     * a batch of the sender's changes, in sequence order, as one step: each is applied as {@link #checkSharedObject}
     * does, or skipped when the replica has it already. It stops at the first change it can do neither with (this node
     * holds no replica of the sender, or is further behind): the leader then sends the whole replica
     */
    SharedObjectChangesResult checkSharedObjectChanges(int senderNodeIndex, List<SharedObjectChange> changes) {
        log.debug("check {} shared-object change(s), sender-node-index: {}", changes == null ? 0 : changes.size(), senderNodeIndex);
        synchronized (replicaLock) {
            if (changes != null) {
                for (var change : changes) {
                    var info = change.toInfo();
                    if (!applyDelta(senderNodeIndex, info) && !containsChange(senderNodeIndex, info)) {
                        log.trace("check shared-object changes, shared-object-sequence mismatch for node-index: {}, leader: {}, this: {}", senderNodeIndex, change.seq, sharedObjectSeq.get(senderNodeIndex));
                        return new SharedObjectChangesResult(false, sharedObjectSeq.get(senderNodeIndex));
                    }
                }
            }
            return new SharedObjectChangesResult(true, sharedObjectSeq.get(senderNodeIndex));
        }
    }

    /** applies a change to a replica that is exactly one change behind it: check, apply and bump as one step */
    private boolean applyDelta(int nodeIndex, SharedObjectInfo sharedObjectInfo) {
        long seq = seqOf(sharedObjectInfo);
        synchronized (replicaLock) {
            var obj = sharedObject.get(nodeIndex);
            var current = sharedObjectSeq.get(nodeIndex);
            if (obj == null || current == null || current + 1 != seq)
                return false;
            if (sharedObjectInfo instanceof MergeSharedObjectInfo mergeSharedObjectInfo) {
                if (mergeSharedObjectInfo.obj != null)
                    mergeObject(nodeIndex, mergeSharedObjectInfo.obj, obj);
            } else {
                for (List<String> path : ((DeleteSharedObjectInfo) sharedObjectInfo).paths)
                    deleteObject(nodeIndex, obj, path);
            }
            sharedObjectSeq.put(nodeIndex, seq);
            return true;
        }
    }

    /** the replica is at the change's sequence or beyond it: a node's content at a sequence is the same everywhere */
    private boolean containsChange(int nodeIndex, SharedObjectInfo sharedObjectInfo) {
        synchronized (replicaLock) {
            var current = sharedObjectSeq.get(nodeIndex);
            return current != null && current >= seqOf(sharedObjectInfo);
        }
    }

    private static long seqOf(SharedObjectInfo sharedObjectInfo) {
        return sharedObjectInfo instanceof MergeSharedObjectInfo ? ((MergeSharedObjectInfo) sharedObjectInfo).seq :
                ((DeleteSharedObjectInfo) sharedObjectInfo).seq;
    }

    private void mergeObject(int nodeIndex, Map<String, Object> obj, Map<String, Object> target) {
        log.debug("merge object for node-index: {}, obj: {}", nodeIndex, obj);
        mergeObject(obj, target);
        log.trace("merge object finished");
    }

    private void mergeObject(Map<String, Object> obj, Map<String, Object> map) {
        for (var entry : obj.entrySet()) {
            if (entry.getValue() instanceof Map) {
                var item = map.containsKey(entry.getKey()) && map.get(entry.getKey()) instanceof Map ?
                        (Map<String, Object>) map.get(entry.getKey()) :
                        new HashMap<String, Object>();
                map.put(entry.getKey(), item);
                mergeObject((Map<String, Object>) entry.getValue(), item);
            } else {
                map.put(entry.getKey(), entry.getValue());
            }
        }
    }


    private boolean deleteObject(int nodeIndex, Map<String, Object> root, List<String> path) {
        log.debug("delete object for node-index: {}, path: {}", nodeIndex, path);
        if (path.isEmpty())
            return false;
        Object item = root;
        var treeList = new LinkedList<Pair<String, Map<String, Object>>>();
        for (String p : path) {
            if (item instanceof Map) {
                treeList.addFirst(new Pair<>(p, (Map<String, Object>) item));
                if (((Map<?, ?>) item).containsKey(p))
                    item = ((Map<?, ?>) item).get(p);
                else
                    return false;
            } else {
                return false;
            }
        }
        var first = treeList.removeFirst();
        first.getValue1().remove(first.getValue0());
        for (var tree : treeList) {
            var child = tree.getValue1().get(tree.getValue0());
            if (((Map<?, ?>) child).isEmpty())
                tree.getValue1().remove(tree.getValue0());
        }
        log.trace("delete object finished");
        return true;
    }

    void mergeSharedObject(Object value, String... path) {
        mergeSharedObjectIf(null, value, path);
    }

    /** see {@link ClusterStarter#mergeSharedObjectIf(Predicate, Object, String...)} */
    boolean mergeSharedObjectIf(Predicate<Map<String, Object>> ownObjectGuard, Object value, String... path) {
        if (path == null || path.length == 0) {
            log.trace("merge shared-object finished, empty path");
            return testOwnObject(ownObjectGuard);
        }
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> item = map;
        for (int i = 0; i < path.length; i++) {
            if (i == path.length - 1) {
                item.put(path[i], value);
            } else {
                Map<String, Object> child = new HashMap<>();
                item.put(path[i], child);
                item = child;
            }
        }
        return mergeOwnObject(map, ownObjectGuard);
    }

    /** @throws IllegalArgumentException when obj cannot be JSON-encoded; nothing is changed then */
    void mergeSharedObject(Map<String, Object> obj) {
        mergeOwnObject(obj, null);
    }

    private boolean mergeOwnObject(Map<String, Object> obj, Predicate<Map<String, Object>> ownObjectGuard) {
        if (obj == null || obj.isEmpty()) {
            log.trace("merge shared-object finished, empty path");
            return testOwnObject(ownObjectGuard);
        }
        // validated before any state changes: a value the leader can never receive must not reach the local object,
        // and the copy also detaches the caller's (possibly live script) objects from the shared object
        Map<String, Object> jsonObj;
        try {
            jsonObj = toJsonMap(obj);
        } catch (Exception e) {
            // the original message leaves out the reference chain, which is 1000 entries long for a self-referencing map
            throw new IllegalArgumentException("shared-object value is not JSON serializable: " +
                    (e instanceof JsonProcessingException jsonException ? jsonException.getOriginalMessage() : e.getMessage()), e);
        }
        Long propagationSeq;
        synchronized (setSharedObjectMutex) {
            log.trace("merge shared-object: {}", jsonObj);
            MergeSharedObjectInfo mergeSharedObjectInfo;
            synchronized (syncMutex) {
                synchronized (replicaLock) {
                    var own = ownObject();
                    if (ownObjectGuard != null && !ownObjectGuard.test(Collections.unmodifiableMap(own))) {
                        log.debug("merge shared-object skipped, guard not satisfied: {}", jsonObj);
                        return false;
                    }
                    mergeObject(clusterStarter.nodeIndex, jsonObj, own);
                    long seq = sharedObjectSeq.get(clusterStarter.nodeIndex) + 1;
                    sharedObjectSeq.put(clusterStarter.nodeIndex, seq);
                    mergeSharedObjectInfo = new MergeSharedObjectInfo(seq, jsonObj);
                }
            }
            propagationSeq = sendToLeader(mergeSharedObjectInfo, targetUrl ->
                            client.mergeSharedObjectToLeader(targetUrl, clusterStarter.nodeIndex, mergeSharedObjectInfo),
                    "merge shared-object to leader");
            log.debug("merge shared-object finished, shared-object-sequence: {}", mergeSharedObjectInfo.seq);
        }
        if (propagationSeq != null)
            awaitPropagated(clusterStarter.nodeIndex, propagationSeq);
        return true;
    }

    void deleteSharedObject(String... path) {
        if (path == null || path.length == 0) {
            log.trace("delete shared-object finished, empty path");
            return;
        }
        deleteSharedObject(Collections.singletonList(Arrays.asList(path)));
    }

    void deleteSharedObject(List<List<String>> paths) {
        deleteSharedObjectIf(null, paths);
    }

    /** see {@link ClusterStarter#deleteSharedObjectIf(Predicate, List)} */
    boolean deleteSharedObjectIf(Predicate<Map<String, Object>> ownObjectGuard, List<List<String>> paths) {
        if (paths == null || paths.isEmpty()) {
            log.trace("delete shared-object finished, empty path");
            return testOwnObject(ownObjectGuard);
        }
        Long propagationSeq = null;
        synchronized (setSharedObjectMutex) {
            log.trace("delete shared-object, paths: {}", paths);
            DeleteSharedObjectInfo deleteSharedObjectInfo;
            synchronized (syncMutex) {
                synchronized (replicaLock) {
                    var own = ownObject();
                    if (ownObjectGuard != null && !ownObjectGuard.test(Collections.unmodifiableMap(own))) {
                        log.debug("delete shared-object skipped, guard not satisfied: {}", paths);
                        return false;
                    }
                    boolean needSync = false;
                    for (List<String> path : paths) {
                        if (deleteObject(clusterStarter.nodeIndex, own, path)) needSync = true;
                    }
                    if (needSync) {
                        long seq = sharedObjectSeq.get(clusterStarter.nodeIndex) + 1;
                        sharedObjectSeq.put(clusterStarter.nodeIndex, seq);
                        // copied: on the leader the paths are sent later by the propagation thread
                        List<List<String>> copiedPaths = new ArrayList<>();
                        for (List<String> path : paths)
                            copiedPaths.add(new ArrayList<>(path));
                        deleteSharedObjectInfo = new DeleteSharedObjectInfo(seq, copiedPaths);
                    } else {
                        deleteSharedObjectInfo = null;
                    }
                }
            }
            if (deleteSharedObjectInfo != null) {
                propagationSeq = sendToLeader(deleteSharedObjectInfo, targetUrl ->
                                client.deleteSharedObjectToLeader(targetUrl, clusterStarter.nodeIndex, deleteSharedObjectInfo),
                        "delete shared-object to leader");
                log.debug("delete shared-object finished, shared-object-sequence: {}", deleteSharedObjectInfo.seq);
            } else {
                log.debug("delete shared-object finished, there is no deleted object");
            }
        }
        if (propagationSeq != null)
            awaitPropagated(clusterStarter.nodeIndex, propagationSeq);
        return true;
    }

    private boolean testOwnObject(Predicate<Map<String, Object>> ownObjectGuard) {
        if (ownObjectGuard == null)
            return true;
        synchronized (replicaLock) {
            return ownObjectGuard.test(Collections.unmodifiableMap(ownObject()));
        }
    }

    /** under replicaLock */
    private Map<String, Object> ownObject() {
        var own = sharedObject.get(clusterStarter.nodeIndex);
        if (own == null)
            throw new IllegalStateException("cluster is not started, no shared-object of this node");
        return own;
    }

    /**
     * the leader applies its own change directly instead of calling itself over HTTP, and its caller then waits until the
     * followers have it (bounded by the probe timeout): a leader that fails right after must not take the change with it.
     * the local change is already applied, so a failed send is not surfaced to the caller: the leader pulls this
     * node's object when the heartbeat sequence no longer matches
     * @return on the leader, the sequence to await once setSharedObjectMutex is released (waiting inside it would queue
     *         every write behind the previous write's propagation); null otherwise, and on a leader without a reachable
     *         peer (nothing was handed off, so there is nothing to wait for)
     */
    private Long sendToLeader(SharedObjectInfo sharedObjectInfo, Consumer<String> toLeader, String name) {
        if (clusterStarter.position == Position.LEADER) {
            // queued in sequence order: this node's changes are made under setSharedObjectMutex
            return queuePropagation(clusterStarter.nodeIndex, sharedObjectInfo) ? seqOf(sharedObjectInfo) : null;
        }
        try {
            redirectFunction.toLeaderFuncConfirmed(toLeader, name);
        } catch (RuntimeException e) {
            log.error("({}) not delivered to leader, left to heartbeat synchronization::{}", name, e.getMessage());
            if (e instanceof CancellationException)
                Thread.currentThread().interrupt();
        }
        return null;
    }

    /** JSON round trip: a detached deep copy holding only JSON types (maps, lists, strings, numbers, booleans, null) */
    private static Map<String, Object> toJsonMap(Map<String, Object> obj) throws IOException {
        return JSON.readValue(JSON.writeValueAsBytes(obj), JSON_MAP);
    }

    /** a detached copy of a stored value, which holds JSON types only: maps and lists are copied, the rest is immutable */
    @SuppressWarnings("unchecked")
    private static <T> T deepCopy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = HashMap.newHashMap(map.size());
            for (var entry : map.entrySet())
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            return (T) copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (var item : list)
                copy.add(deepCopy(item));
            return (T) copy;
        }
        return value;
    }

    /** a copy of the value at path, detached from the shared object */
    Object getItem(int nodeIndex, String... path) {
        synchronized (replicaLock) {
            Object item = sharedObject.get(nodeIndex);
            for (String p : path) {
                if (item instanceof Map) {
                    if (((Map<?, ?>) item).containsKey(p))
                        item = ((Map<?, ?>) item).get(p);
                    else
                        return null;
                } else {
                    return null;
                }
            }
            return deepCopy(item);
        }
    }

    /** see {@link ClusterStarter#readSharedObject(Function)} */
    <T> T readSharedObject(Function<Map<Integer, Map<String, Object>>, T> reader) {
        synchronized (replicaLock) {
            return reader.apply(Collections.unmodifiableMap(sharedObject));
        }
    }

    /** copied one replica at a time: a large copy does not hold up every change until it is complete */
    Map<Integer, Map<String, Object>> copySharedObjectMap() {
        Map<Integer, Map<String, Object>> copy = new HashMap<>();
        for (int nodeIndex : heldNodeIndexes(false)) {
            synchronized (replicaLock) {
                var obj = sharedObject.get(nodeIndex);
                if (obj != null)
                    copy.put(nodeIndex, deepCopy(obj));
            }
        }
        return copy;
    }

    /** node-indexes of the replicas held (pending removals included, if asked) */
    private Set<Integer> heldNodeIndexes(boolean includePendingRemovals) {
        synchronized (replicaLock) {
            var held = new HashSet<>(sharedObject.keySet());
            if (includePendingRemovals)
                held.addAll(pendingRemovals.keySet());
            return held;
        }
    }

    Map<Integer, Long> copySharedObjectSeq() {
        synchronized (replicaLock) {
            return new HashMap<>(sharedObjectSeq);
        }
    }

    /** the sequences sent with heartbeats: pending removals included, so followers keep those replicas until the failover finished */
    private Map<Integer, Long> heartbeatSequences() {
        synchronized (replicaLock) {
            var sequences = new HashMap<>(sharedObjectSeq);
            for (var entry : pendingRemovals.entrySet())
                sequences.putIfAbsent(entry.getKey(), entry.getValue().seq);
            return sequences;
        }
    }

    /** each replica is copied with its sequence as one step, one replica at a time */
    SharedObject copySharedObject(boolean includePendingRemovals) {
        Map<Integer, Map<String, Object>> objects = new HashMap<>();
        Map<Integer, Long> sequences = new HashMap<>();
        for (int nodeIndex : heldNodeIndexes(includePendingRemovals)) {
            var copy = copyReplica(nodeIndex, includePendingRemovals);
            if (copy != null) {
                objects.put(nodeIndex, copy.obj);
                sequences.put(nodeIndex, copy.seq);
            }
        }
        return new SharedObject(objects, sequences);
    }

    /** null when no replica of nodeIndex is held */
    MergeSharedObjectInfo copyReplica(int nodeIndex, boolean includePendingRemovals) {
        synchronized (replicaLock) {
            var obj = sharedObject.get(nodeIndex);
            if (obj != null)
                return new MergeSharedObjectInfo(sharedObjectSeq.get(nodeIndex), deepCopy(obj));
            var pending = includePendingRemovals ? pendingRemovals.get(nodeIndex) : null;
            return pending == null ? null : new MergeSharedObjectInfo(pending.seq, deepCopy(pending.obj));
        }
    }

    /** null when no replica of nodeIndex is held */
    private Long replicaSeq(int nodeIndex) {
        synchronized (replicaLock) {
            return sharedObjectSeq.get(nodeIndex);
        }
    }

    /** node-indexes whose sequence here differs from the leader's (a missing replica included) */
    Set<Integer> mismatchedSequences(Map<Integer, Long> leaderSeq) {
        var result = new HashSet<Integer>();
        synchronized (replicaLock) {
            for (var entry : leaderSeq.entrySet()) {
                if (entry.getKey() != clusterStarter.nodeIndex && !Objects.equals(entry.getValue(), sharedObjectSeq.get(entry.getKey())))
                    result.add(entry.getKey());
            }
        }
        return result;
    }

    /** every replica held, pending removals included, as sequence and content digest */
    SharedObjectDigests digests() {
        var digests = new HashMap<Integer, ReplicaDigest>();
        for (int nodeIndex : heldNodeIndexes(true)) {
            var digest = digestOf(nodeIndex, true);
            if (digest != null)
                digests.put(nodeIndex, digest);
        }
        return new SharedObjectDigests(clusterStarter.nodeIndex, digests);
    }

    /** only this node's own object, as sequence and content digest */
    SharedObjectDigests ownDigest() {
        var digests = new HashMap<Integer, ReplicaDigest>();
        var own = digestOf(clusterStarter.nodeIndex, false);
        if (own != null)
            digests.put(clusterStarter.nodeIndex, own);
        return new SharedObjectDigests(clusterStarter.nodeIndex, digests);
    }

    private ReplicaDigest digestOf(int nodeIndex, boolean includePendingRemovals) {
        var copy = copyReplica(nodeIndex, includePendingRemovals);
        return copy == null ? null : new ReplicaDigest(copy.seq, digest(copy.obj));
    }

    static String digest(Map<String, Object> obj) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(CANONICAL_JSON.writeValueAsBytes(obj));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("shared-object digest failed::" + e.getMessage(), e);
        }
    }

    /**
     * defence in depth, every few heartbeats: an equal sequence is taken for equal content everywhere else, so content
     * that diverged anyway would otherwise never be repaired
     */
    private void antiEntropy() {
        if (!antiEntropyRunning.compareAndSet(false, true))
            return;
        try {
            if (clusterStarter.position == Position.LEADER)
                repairMemberObjects();
            else if (clusterStarter.position == Position.FOLLOWER)
                repairFromLeader();
        } catch (Exception e) {
            log.debug("shared-object anti-entropy failed::{}", e.getMessage());
        } finally {
            antiEntropyRunning.set(false);
        }
    }

    /** follower: replicas with the leader's sequence but other content are fetched from the leader again */
    private void repairFromLeader() {
        int leader = leaderIndex;
        if (leader == 0 || leader == clusterStarter.nodeIndex)
            return;
        var ret = redirectFunction.toIndexFunc(leader, targetUrl -> {
            SharedObjectDigests remote;
            try {
                remote = client.getSharedObjectDigests(targetUrl);
            } catch (NodeHttpClient.CallException e) {
                // a leader of an older version, without the route
                if (e.getStatusCode() == 404) return;
                throw e;
            }
            if (remote == null || remote.digests == null)
                return;
            var local = digests().digests;
            for (var entry : remote.digests.entrySet()) {
                var mine = local.get(entry.getKey());
                var theirs = entry.getValue();
                if (entry.getKey() == clusterStarter.nodeIndex || mine == null || theirs == null ||
                        mine.seq != theirs.seq || Objects.equals(mine.digest, theirs.digest))
                    continue;
                log.info("replica of node-index: {} differs from the leader's at the same shared-object-sequence: {}, fetch it", entry.getKey(), mine.seq);
                if (fetchReplica(targetUrl, entry.getKey(), mine.seq))
                    log.info("replica of node-index: {} repaired", entry.getKey());
            }
        }, "shared-object anti-entropy");
        if (ret != null)
            log.debug("shared-object anti-entropy with the leader failed::{}", ret.getMessage());
    }

    /** leader: its copy of each member's object is compared with the member's own and pulled again when it differs */
    private void repairMemberObjects() {
        Set<Integer> checked = ConcurrentHashMap.newKeySet();
        redirectFunction.parallelExecute(clusterStarter.nodeTargetUrls, targetUrl -> {
            SharedObjectDigests remote;
            try {
                // only the member's own object (a node of an older version answers with all of them)
                remote = client.getOwnSharedObjectDigest(targetUrl);
            } catch (RuntimeException e) {
                // unreachable, or a node of an older version without the route (404)
                log.trace("get shared-object digest (url={}) failed::{}", targetUrl, e.getMessage());
                return;
            }
            if (remote == null || remote.digests == null || remote.nodeIndex == clusterStarter.nodeIndex || !checked.add(remote.nodeIndex))
                return;
            int owner = remote.nodeIndex;
            var theirs = remote.digests.get(owner);
            var mine = digestOf(owner, false);
            if (theirs == null || mine == null || mine.seq != theirs.seq || Objects.equals(mine.digest, theirs.digest))
                return;
            log.info("replica of node-index: {} differs from the node's own object at the same shared-object-sequence: {}, pull it", owner, mine.seq);
            synchronized (syncMutex) {
                var info = client.getSharedObject(targetUrl);
                if (info != null && info.obj != null) {
                    overwriteSharedObject(owner, info);
                    log.info("replica of node-index: {} repaired", owner);
                }
            }
        });
    }

    private void startStallTicker() {
        lastTickNanos = System.nanoTime();
        var ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long simulated = simulatedStallMillis;
                    if (simulated > 0) {
                        simulatedStallMillis = 0;
                        Thread.sleep(simulated);
                    }
                    Thread.sleep(STALL_TICK_MILLIS);
                    long now = System.nanoTime();
                    long gap = now - lastTickNanos;
                    if (gap > STALL_THRESHOLD_NANOS) {
                        lastStallEndNanos = now;
                        stallSeen = true;
                        log.warn("this node stalled for {}[ms], lost-node timers are re-armed until peers are heard again", TimeUnit.NANOSECONDS.toMillis(gap));
                    }
                    lastTickNanos = now;
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    // never ends while running: a stopped ticker would look like a stall forever, and no peer would be removed
                    lastTickNanos = System.nanoTime();
                    try {
                        log.error("cluster stall ticker failed, continue", e);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "cluster-stall-ticker");
        ticker.setDaemon(true);
        stallTicker = ticker;
        ticker.start();
    }

    private void stopStallTicker() {
        var ticker = stallTicker;
        stallTicker = null;
        if (ticker != null)
            ticker.interrupt();
    }

    /**
     * true while this JVM is stalled or was shortly before (GC pause, docker pause, SIGSTOP): overdue timers then fire
     * before the heartbeats that queued up meanwhile are processed, so their timeout says nothing about the peer
     */
    boolean recentlyStalled() {
        if (stallTicker == null)
            return false;
        long now = System.nanoTime();
        // a timer firing before the ticker woke up
        if (now - lastTickNanos > STALL_THRESHOLD_NANOS)
            return true;
        long window = TimeUnit.SECONDS.toNanos(clusterStarter.leaderLostTimeoutSeconds) +
                TimeUnit.MILLISECONDS.toNanos(2L * clusterStarter.heartbeatSendingIntervalMillis);
        return stallSeen && now - lastStallEndNanos < window;
    }

    /** test hook: the stall ticker sleeps for millis, as it would in a paused JVM */
    void simulateStall(long millis) {
        simulatedStallMillis = millis;
    }

    private static class SharedObjectInfo {}

    /**
     * one sender's changes on their way to one peer, in sequence order, or (past the bound) its whole replica instead
     * (guarded by propagationLanes)
     */
    private static final class PropagationLane {
        final int senderNodeIndex;
        final String url;
        final List<SharedObjectInfo> changes = new ArrayList<>();
        boolean wholeReplica = false;
        /** the newest sequence queued, and the newest whose batch has been answered or given up */
        long queuedSeq = Long.MIN_VALUE;
        long doneSeq = Long.MIN_VALUE;
        /** a task sends the lane's batches */
        boolean draining = false;
        /** the last batch took longer than the budget, or failed: the leader's own changes do not wait for this peer */
        boolean lagging = false;

        PropagationLane(int senderNodeIndex, String url) {
            this.senderNodeIndex = senderNodeIndex;
            this.url = url;
        }
    }

    /** a leader as its followers tell it apart: a restarted or re-elected one is another leader */
    private record LeaderId(int index, long transitionTime) {}

    /** a leader's heartbeat listed a replica at seq, or a newer one */
    private record Listing(LeaderId leader, Long seq) {}

    /**
     * a failover of an object at seq, or older, that finished on a node no longer leading (see {@link #softRemovals}).
     * leading: this node's last transition time if it led when it learned of it, null otherwise
     */
    private record SoftRemoval(long seq, ZonedDateTime leading) {}

    /**
     * failed handover calls to one leader in a row, and when the next may be sent: kept across handovers, so that offers
     * made again and again to a leader this node cannot reach back off too, until this node's heartbeat reaches that
     * leader again
     */
    private static final class HandoverBackoff {
        private LeaderId leader = null;
        private int failures = 0;
        private long nextAttemptNanos = 0;

        synchronized boolean due(LeaderId target) {
            return !target.equals(leader) || System.nanoTime() - nextAttemptNanos >= 0;
        }

        /** the next call to target after 1, 2, 4, 8 and then 16 heartbeat intervals. @return the failures in a row */
        synchronized int failed(LeaderId target, long intervalMillis) {
            if (!target.equals(leader)) {
                leader = target;
                failures = 0;
            }
            failures = Math.min(failures + 1, MAX_HANDOVER_BACKOFF_EXPONENT + 1);
            nextAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(intervalMillis << (failures - 1));
            return failures;
        }

        synchronized void succeeded(LeaderId target) {
            if (target.equals(leader))
                reset();
        }

        /** this node's heartbeat reached leaderIndex again, after none did for a while: due right away */
        synchronized void reached(int leaderIndex) {
            if (leader != null && leader.index() == leaderIndex)
                reset();
        }

        synchronized void reset() {
            leader = null;
            failures = 0;
        }
    }

    /** replicas handed over to the leader: all a demoted split-brain leader held, or those a new leader lacks (offer) */
    private static final class Handover {
        /** the same for each attempt, so the winner resolves the split brain once */
        final String id = UUID.randomUUID().toString();
        /** an offer, which the leader takes over only for nodes that are not members; otherwise a split-brain handover */
        final boolean offer;
        final SharedObject object;
        /** the leader it goes to: only that leader's heartbeats tell whether it was delivered */
        final LeaderId target;
        /** offer: the node-indexes the leader answered for (took over, held as new, or knew to be removed) */
        final Set<Integer> answered = ConcurrentHashMap.newKeySet();
        /** the leader answered for all of it: the pending handover is cleared with the leader's next heartbeat */
        volatile boolean delivered = false;
        /**
         * split brain: the winner's heartbeat listed all of it before any call was answered. Nothing is kept for it any
         * more, but the winner is still told once (it resolves the split brain then)
         */
        volatile boolean covered = false;
        /** a call was sent: the first one of a split-brain handover goes out whatever the backoff */
        volatile boolean attempted = false;
        /**
         * split brain: the node-indexes of the objects that a call answered by the winner carried (it took them over, or
         * holds them as new, or knew them removed), and those of the call on its way to a winner that this node's
         * heartbeats reach. A failover of one of them that finishes here after the demotion leaves this node holding it
         * again (see completeRemoval). Changed under replicaLock
         */
        final Set<Integer> handedOver = ConcurrentHashMap.newKeySet();
        volatile Set<Integer> sending = Set.of();

        Handover(boolean offer, SharedObject object, LeaderId target) {
            this.offer = offer;
            this.object = object;
            this.target = target;
        }

        /** node-index -> sequence of the replicas the reconcile keeps meanwhile */
        Map<Integer, Long> held() {
            return covered ? Map.of() : object.sharedObjectSeq;
        }

        @Override
        public String toString() {
            return (offer ? "offer" : "split brain handover") + " of the shared-objects of node-indexes: " + object.sharedObjectSeq.keySet();
        }
    }

    @Getter
    @ToString
    @NoArgsConstructor
    static class MergeSharedObjectInfo extends SharedObjectInfo {
        long seq;
        Map<String, Object> obj;

        MergeSharedObjectInfo(long seq, Map<String, Object> obj) {
            this.seq = seq;
            this.obj = obj;
        }
    }

    @Getter
    @ToString
    @NoArgsConstructor
    static class DeleteSharedObjectInfo extends SharedObjectInfo {
        long seq;
        List<List<String>> paths;

        DeleteSharedObjectInfo(long seq, List<List<String>> paths) {
            this.seq = seq;
            this.paths = paths;
        }
    }

    /** one change in a batch sent to a peer: a merge (obj) or a delete (paths) */
    @Getter
    @ToString
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SharedObjectChange {
        long seq;
        Map<String, Object> obj;
        List<List<String>> paths;

        SharedObjectChange(long seq, Map<String, Object> obj, List<List<String>> paths) {
            this.seq = seq;
            this.obj = obj;
            this.paths = paths;
        }

        static List<SharedObjectChange> of(List<SharedObjectInfo> changes) {
            List<SharedObjectChange> batch = new ArrayList<>(changes.size());
            for (var change : changes) {
                if (change instanceof MergeSharedObjectInfo merge)
                    batch.add(new SharedObjectChange(merge.seq, merge.obj, null));
                else
                    batch.add(new SharedObjectChange(((DeleteSharedObjectInfo) change).seq, null, ((DeleteSharedObjectInfo) change).paths));
            }
            return batch;
        }

        SharedObjectInfo toInfo() {
            return paths != null ? new DeleteSharedObjectInfo(seq, paths) : new MergeSharedObjectInfo(seq, obj);
        }
    }

    /** a peer's answer to a batch: all applied (or held already), and its sequence of the sender afterwards (null: no replica) */
    @Getter
    @ToString
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SharedObjectChangesResult {
        boolean applied;
        Long seq;

        SharedObjectChangesResult(boolean applied, Long seq) {
            this.applied = applied;
            this.seq = seq;
        }
    }

    @Getter
    @ToString
    @NoArgsConstructor
    static class SharedObject {
        Map<Integer, Map<String, Object>> sharedObject;
        Map<Integer, Long> sharedObjectSeq;

        SharedObject(Map<Integer, Map<String, Object>> sharedObject, Map<Integer, Long> sharedObjectSeq) {
            this.sharedObject = sharedObject;
            this.sharedObjectSeq = sharedObjectSeq;
        }
    }

    @Getter
    @ToString
    @NoArgsConstructor
    static class ReplicaDigest {
        long seq;
        String digest;

        ReplicaDigest(long seq, String digest) {
            this.seq = seq;
            this.digest = digest;
        }
    }

    @Getter
    @ToString
    @NoArgsConstructor
    static class SharedObjectDigests {
        int nodeIndex;
        Map<Integer, ReplicaDigest> digests;

        SharedObjectDigests(int nodeIndex, Map<Integer, ReplicaDigest> digests) {
            this.nodeIndex = nodeIndex;
            this.digests = digests;
        }
    }
}
