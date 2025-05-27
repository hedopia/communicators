package com.sds.communicators.cluster;

import com.sds.communicators.common.type.Position;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
class ClusterService {
    private final ClusterStarter clusterStarter;
    private final RedirectFunction redirectFunction;
    private final ClusterClient client;
    private final String clusterBasePath;

    final ClusterEvents clusterEvents = new ClusterEvents();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final PublishProcessor<Position> leaderTimer = PublishProcessor.create();
    private final PublishProcessor<Integer> nodeTimer = PublishProcessor.create();
    final Map<Integer, Map<String, Object>> sharedObject = new ConcurrentHashMap<>();
    final Map<Integer, Long> sharedObjectSeq = new ConcurrentHashMap<>();

    private ZonedDateTime lastTransitionTime = ZonedDateTime.now();
    private int maxClusterSize = 0;
    private boolean initialPosition = true;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Disposable> nodes = new ConcurrentHashMap<>();
    private final Object setSharedObjectMutex = new Object();
    private final Object heartbeatMutex = new Object();
    private final Object syncMutex = new Object();

    ClusterService(ClusterStarter clusterStarter, RedirectFunction redirectFunction, ClusterClient client, String clusterBasePath) {
        this.clusterStarter = clusterStarter;
        this.redirectFunction = redirectFunction;
        this.client = client;
        this.clusterBasePath = clusterBasePath;
    }

    void start() throws InterruptedException {
        sharedObject.put(clusterStarter.nodeIndex, new HashMap<>());
        sharedObjectSeq.put(clusterStarter.nodeIndex, 0L);
        long initialDelay = (long)(clusterStarter.leaderLostTimeoutSeconds * 1.5);
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
        initialPosition = true;
        disposables.clear();
        sharedObject.clear();
        sharedObjectSeq.clear();
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
        var res = redirectFunction.toIndexFunc(nodeIndex, targetUrl ->
                ret.set(client.getClient(targetUrl).getNodeStatus(clusterBasePath).getPosition()), "get position for node-index: " + nodeIndex);
        if (res != null) throw res;
        return ret.get();
    }

    private void heartbeat() {
        log.info("position: {} (last transition time: {})", clusterStarter.position, lastTransitionTime.toString());
        Schedulers.io().scheduleDirect(() -> {
            try {
                long begin = System.currentTimeMillis();
                if (clusterStarter.position == Position.LEADER) {
                    synchronized (heartbeatMutex) {
                        sendHeartbeat(clusterStarter.position);
                    }
                } else {
                    sendHeartbeat(clusterStarter.position);
                }
                log.trace("send heartbeat success, elapsed time: {}[ms]", System.currentTimeMillis() - begin);
            } catch (Exception e) {
                log.error("send heartbeat failed::{}", e.getMessage());
            }
        });
    }

    private void sendHeartbeat(Position position) {
        redirectFunction.toAllFunc(targetUrl ->
                client.getClient(targetUrl).heartbeat(
                        clusterBasePath,
                        clusterStarter.nodeIndex,
                        position,
                        sharedObjectSeq), "send heartbeat");
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
                        sendHeartbeat(Position.LEADER);
                        disposables.add(
                                Schedulers.io().scheduleDirect(() -> {
                                    for (var action : clusterEvents.becomeLeaderEvents) {
                                        try {
                                            action.getValue1().run();
                                        } catch (Throwable e) {
                                            log.error("become leader events [{}] failed", action.getValue0(), e);
                                        }
                                    }
                                })
                        );
                    } else {
                        disposables.add(
                                Schedulers.io().scheduleDirect(() -> {
                                    for (var action : clusterEvents.becomeFollowerEvents) {
                                        try {
                                            action.getValue1().run();
                                        } catch (Throwable e) {
                                            log.error("become follower events [{}] failed", action.getValue0(), e);
                                        }
                                    }
                                })
                        );

                        disposables.add(
                                leaderTimer.filter(p -> p == Position.LEADER)
                                        .timeout(clusterStarter.leaderLostTimeoutSeconds, TimeUnit.SECONDS, Schedulers.io())
                                        .subscribe(__ -> log.trace("Heartbeat received from leader"),
                                                e -> redirectFunction.electLeader())
                        );
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

    void heartbeatReceived(int nodeIndex, Position position, Map<Integer, Long> receivedSharedObjectSeq) {
        if (initialPosition && position == Position.LEADER)
            initialPosition = false;

        if (clusterStarter.position == Position.LEADER &&
                nodeIndex != clusterStarter.nodeIndex &&
                position == Position.LEADER) {
            log.error("unexpected heartbeat received from leader, set to follower");
            transition(Position.FOLLOWER);

            synchronized (syncMutex) {
                redirectFunction.toLeaderFuncConfirmed(targetUrl ->
                                client.getClient(targetUrl).syncSharedObject(
                                        clusterBasePath,
                                        clusterStarter.nodeIndex,
                                        new SharedObject(sharedObject, sharedObjectSeq)),
                        "synchronize split brain leader shared object");
            }
        }

        leaderTimer.onNext(position);

        if (nodes.containsKey(nodeIndex))
            nodeTimer.onNext(nodeIndex);
        else
            clusterAdded(nodeIndex);

        if (position == Position.LEADER) {
            for (var entry : receivedSharedObjectSeq.entrySet()) {
                if (entry.getKey() != clusterStarter.nodeIndex) {
                    sharedObject.putIfAbsent(entry.getKey(), new HashMap<>());
                    sharedObjectSeq.putIfAbsent(entry.getKey(), 0L);
                    if (!entry.getValue().equals(sharedObjectSeq.get(entry.getKey()))) {
                        log.debug("heartbeat shared-object-sequence mismatch for node-index: {}, leader: {}, this: {}", entry.getKey(), entry.getValue(), sharedObjectSeq.get(entry.getKey()));
                        AtomicReference<MergeSharedObjectInfo> receivedSharedObjectInfo = new AtomicReference<>();
                        var ret = redirectFunction.toLeaderFunc(targetUrl -> {
                            receivedSharedObjectInfo.set(client.getClient(targetUrl).getSharedObject(clusterBasePath, entry.getKey()));
                        }, "get shared object");
                        if (ret == null) {
                            log.trace("get shared object for sync follower from node-index: {} success", entry.getKey());
                            overwriteSharedObject(entry.getKey(), receivedSharedObjectInfo.get());
                        } else {
                            log.error("get shared object for sync follower from node-index: {} failed", entry.getKey(), ret);
                        }
                    } else {
                        log.trace("heartbeat shared-object-sequence match for node-index: {}", entry.getKey());
                    }
                }
            }
        }

        if (clusterStarter.position == Position.LEADER) {
            if (sharedObjectSeq.get(nodeIndex) == null || !sharedObjectSeq.get(nodeIndex).equals(receivedSharedObjectSeq.get(nodeIndex)))
                synchronized (syncMutex) {
                    overwriteLeaderSharedObject(nodeIndex);
                }
        }
    }

    private String overwriteLeaderSharedObject(int nodeIndex) {
        log.debug("overwrite leader shared object for node-index: {}", nodeIndex);
        AtomicReference<MergeSharedObjectInfo> receivedSharedObjectInfo = new AtomicReference<>();
        var ret = redirectFunction.toIndexFunc(nodeIndex, targetUrl ->
                receivedSharedObjectInfo.set(client.getClient(targetUrl).getSharedObject(clusterBasePath)), "get shared object");
        if (ret == null) {
            log.trace("get shared object for sync leader from node-index: {} success", nodeIndex);
            overwriteSharedObject(nodeIndex, receivedSharedObjectInfo.get());
            return null;
        } else {
            log.error("get shared object for sync leader from node-index: {} failed", nodeIndex, ret);
            return "get shared object for sync leader from node-index: " + nodeIndex + " failed";
        }
    }

    private void clusterAdded(int nodeIndex) {
        log.info("cluster node added, nodeIndex: {}", nodeIndex);
        nodes.computeIfAbsent(nodeIndex, key -> {
            for (var action : clusterEvents.clusterAddedEvents) {
                try {
                    action.getValue1().accept(key);
                } catch (Throwable e) {
                    log.error("cluster(node-index: {}) added events [{}] failed", key, action.getValue0(), e);
                }
            }
            verifyActivation();
            return nodeTimer.filter(r -> r.equals(key))
                    .timeout(clusterStarter.leaderLostTimeoutSeconds, TimeUnit.SECONDS, Schedulers.io())
                    .subscribe(index -> log.trace("Heartbeat received from node-index: {}", index)
                            , e -> clusterDeleted(key));
        });
    }

    void clusterDeleted(int nodeIndex) {
        Disposable removed = nodes.remove(nodeIndex);
        if (removed != null) {
            removed.dispose();
            log.info("cluster node removed, nodeIndex: {}", nodeIndex);
            redirectFunction.toLeaderFuncConfirmed(targetUrl ->
                            client.getClient(targetUrl).removeSharedObject(clusterBasePath, nodeIndex),
                    "remove shared object");
            sharedObject.remove(nodeIndex);
            verifyActivation();
        } else {
            log.trace("node-index: {}, already deleted", nodeIndex);
        }
    }

    void removeSharedObject(int nodeIndex) {
        var removed = sharedObject.remove(nodeIndex);
        if (removed != null) {
            log.debug("node-index: {}, removed shared object process", nodeIndex);
            sharedObjectSeq.remove(nodeIndex);
            redirectFunction.toAllFunc(targetUrl -> client.getClient(targetUrl).clusterDeleted(clusterBasePath, nodeIndex), "cluster deleted");
            for (var action : clusterEvents.clusterDeletedEvents) {
                try {
                    action.getValue1().accept(nodeIndex, removed);
                } catch (Throwable e) {
                    log.error("cluster(node-index: {}) deleted events [{}] failed, object: {}", nodeIndex, action.getValue0(), removed, e);
                }
            }
        }
    }

    private void verifyActivation() {
        if (nodes.size() > maxClusterSize)
            maxClusterSize = nodes.size();
        int quorum = clusterStarter.quorum;
        if (quorum <= 0)
            quorum = maxClusterSize/2 + 1;
        log.trace("current quorum: {}", quorum);

        if (nodes.size() < quorum && clusterStarter.isActivated) {
            log.info("application inactivated");
            clusterStarter.isActivated = false;
            for (var action : clusterEvents.inactivatedEvents) {
                try {
                    action.getValue1().run();
                } catch (Throwable e) {
                    log.error("inactivated events [{}] failed", action.getValue0(), e);
                }
            }
        }
        else if (nodes.size() >= quorum && !clusterStarter.isActivated) {
            log.info("application activated");
            clusterStarter.isActivated = true;
            for (var action : clusterEvents.activatedEvents) {
                try {
                    action.getValue1().run();
                } catch (Throwable e) {
                    log.error("activated events [{}] failed", action.getValue0(), e);
                }
            }
        }
    }

    String setSharedObjectToLeader(int senderNodeIndex, SharedObjectInfo sharedObjectInfo) {
        synchronized (syncMutex) {
            if (clusterStarter.position == Position.LEADER) {
                log.trace("set shared object to leader, sender-node-index: {}, shared-object-info: {}", senderNodeIndex, sharedObjectInfo);
                var seq = sharedObjectInfo instanceof MergeSharedObjectInfo ? ((MergeSharedObjectInfo) sharedObjectInfo).seq :
                        ((DeleteSharedObjectInfo) sharedObjectInfo).seq;
                if (senderNodeIndex != clusterStarter.nodeIndex) {
                    sharedObject.putIfAbsent(senderNodeIndex, new HashMap<>());
                    sharedObjectSeq.putIfAbsent(senderNodeIndex, 0L);
                    if (sharedObjectSeq.get(senderNodeIndex) != seq) {
                        log.trace("set shared object to leader, shared-object-sequence mismatch for node-index: {}, leader: {}, sender: {}", senderNodeIndex, sharedObjectSeq.get(senderNodeIndex), seq);
                        var ret = overwriteLeaderSharedObject(senderNodeIndex);
                        if (ret != null) return ret;
                    } else {
                        log.trace("set shared object to leader, shared-object-sequence match for node-index: {}", senderNodeIndex);
                        if (sharedObjectInfo instanceof MergeSharedObjectInfo)
                            mergeObject(senderNodeIndex, ((MergeSharedObjectInfo) sharedObjectInfo).obj);
                        else
                            for (List<String> path : ((DeleteSharedObjectInfo) sharedObjectInfo).paths)
                                deleteObject(senderNodeIndex, path);
                    }
                }

                synchronized (heartbeatMutex) {
                    log.trace("propagate shared object for node-index: {}", senderNodeIndex);
                    var results = new ConcurrentHashMap<String, Boolean>();
                    if (sharedObjectInfo instanceof MergeSharedObjectInfo)
                        redirectFunction.toAllFunc(targetUrl ->
                                        results.put(targetUrl, client.getClient(targetUrl).checkMergeSharedObject(clusterBasePath, senderNodeIndex, (MergeSharedObjectInfo) sharedObjectInfo)),
                                "check merge shared object");
                    else
                        redirectFunction.toAllFunc(targetUrl ->
                                        results.put(targetUrl, client.getClient(targetUrl).checkDeleteSharedObject(clusterBasePath, senderNodeIndex, (DeleteSharedObjectInfo) sharedObjectInfo)),
                                "check delete shared object");
                    var needSyncUrls = results.entrySet().stream().filter(entry -> !entry.getValue()).map(Map.Entry::getKey).collect(Collectors.toList());
                    redirectFunction.parallelExecute(needSyncUrls, targetUrl ->
                            client.getClient(targetUrl).overwriteSharedObject(clusterBasePath, senderNodeIndex,
                                    new MergeSharedObjectInfo(sharedObjectSeq.get(senderNodeIndex) + 1, sharedObject.get(senderNodeIndex))));
                    if (senderNodeIndex != clusterStarter.nodeIndex)
                        sharedObjectSeq.put(senderNodeIndex, sharedObjectSeq.get(senderNodeIndex) + 1);
                }
                return null;
            } else {
                log.error("set shared object to leader ignored, position is not leader, sender-node-index: {}, shared-object-info: {}", senderNodeIndex, sharedObjectInfo);
                return "set shared object to leader ignored, position is not leader";
            }
        }
    }

    void syncSharedObject(SharedObject object) {
        log.trace("synchronize split brain nodes start");
        synchronized (syncMutex) {
            for (var nodeIndex : object.sharedObject.keySet()) {
                sharedObject.putIfAbsent(nodeIndex, object.sharedObject.get(nodeIndex));
                sharedObjectSeq.putIfAbsent(nodeIndex, object.sharedObjectSeq.get(nodeIndex));
            }
            var results = new ConcurrentHashMap<String, Set<Integer>>();
            redirectFunction.toAllFunc(targetUrl ->
                            results.put(targetUrl, client.getClient(targetUrl).checkSharedObjectSeq(clusterBasePath, sharedObjectSeq)),
                    "check shared-object-sequence");
            var syncList = results.entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream().map(nodeIndex -> new Pair<>(entry.getKey(), nodeIndex)))
                    .collect(Collectors.toList());
            redirectFunction.parallelExecute(syncList, sync ->
                    client.getClient(sync.getValue0()).overwriteSharedObject(clusterBasePath, sync.getValue1(),
                            new MergeSharedObjectInfo(sharedObjectSeq.get(sync.getValue1()), sharedObject.get(sync.getValue1()))));
        }
        log.trace("synchronize split brain nodes end");
    }

    void overwriteSharedObject(int nodeIndex, MergeSharedObjectInfo sharedObjectInfo) {
        log.trace("overwrite shared object, sender-node-index: {}, shared-object-info: {}", nodeIndex, sharedObjectInfo);
        sharedObject.put(nodeIndex, sharedObjectInfo.obj);
        sharedObjectSeq.put(nodeIndex, sharedObjectInfo.seq);
        Schedulers.io().scheduleDirect(() -> {
            for (var action : clusterEvents.overwrittenEvents) {
                try {
                    action.getValue1().run();
                } catch (Throwable e) {
                    log.error("overwritten events [{}] failed", action.getValue0(), e);
                }
            }
        });
    }

    boolean checkSharedObject(int senderNodeIndex, SharedObjectInfo sharedObjectInfo) {
        log.trace("check shared object, sender-node-index: {}, shared-object-info: {}", senderNodeIndex, sharedObjectInfo);
        sharedObject.putIfAbsent(senderNodeIndex, new HashMap<>());
        sharedObjectSeq.putIfAbsent(senderNodeIndex, 0L);
        var seq = sharedObjectInfo instanceof MergeSharedObjectInfo ? ((MergeSharedObjectInfo) sharedObjectInfo).seq :
                ((DeleteSharedObjectInfo) sharedObjectInfo).seq;
        if (sharedObjectSeq.get(senderNodeIndex) == seq) {
            log.trace("received shared-object-sequence match for node-index: {}", senderNodeIndex);
            if (sharedObjectInfo instanceof MergeSharedObjectInfo)
                mergeObject(senderNodeIndex, ((MergeSharedObjectInfo) sharedObjectInfo).obj);
            else
                for (List<String> path : ((DeleteSharedObjectInfo) sharedObjectInfo).paths)
                    deleteObject(senderNodeIndex, path);
            sharedObjectSeq.put(senderNodeIndex, sharedObjectSeq.get(senderNodeIndex) + 1);
            return true;
        } else {
            log.trace("checkSharedObject shared-object-sequence mismatch for node-index: {}, leader: {}, this: {}", senderNodeIndex, seq, sharedObjectSeq.get(senderNodeIndex));
            return false;
        }
    }

    private void mergeObject(int nodeIndex, Map<String, Object> obj) {
        log.trace("merge object for node-index: {}, obj: {}", nodeIndex, obj);
        mergeObject(obj, sharedObject.get(nodeIndex));
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


    private boolean deleteObject(int nodeIndex, List<String> path) {
        log.trace("delete object for node-index: {}, path: {}", nodeIndex, path);
        Object item = sharedObject.get(nodeIndex);
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
        if (path == null || path.length == 0) {
            log.trace("merge shared object finished, empty path");
            return;
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
        mergeSharedObject(map);
    }

    void mergeSharedObject(Map<String, Object> obj) {
        if (obj == null || obj.isEmpty()) {
            log.trace("merge shared object finished, empty path");
            return;
        }
        synchronized (setSharedObjectMutex) {
            log.trace("merge shared object: {}", obj);
            mergeObject(clusterStarter.nodeIndex, obj);
            var mergeSharedObjectInfo = new MergeSharedObjectInfo(sharedObjectSeq.get(clusterStarter.nodeIndex), obj);
            redirectFunction.toLeaderFuncConfirmed(targetUrl ->
                            client.getClient(targetUrl).mergeSharedObjectToLeader(clusterBasePath, clusterStarter.nodeIndex, mergeSharedObjectInfo),
                    "merge shared object to leader");
            sharedObjectSeq.put(clusterStarter.nodeIndex, sharedObjectSeq.get(clusterStarter.nodeIndex) + 1);
            log.debug("merge shared object finished, shared-object-sequence: {}", sharedObjectSeq.get(clusterStarter.nodeIndex));
        }
    }

    void deleteSharedObject(String... path) {
        if (path == null) {
            log.trace("delete shared object finished, empty path");
            return;
        }
        deleteSharedObject(Collections.singletonList(Arrays.asList(path)));
    }

    void deleteSharedObject(List<List<String>> paths) {
        if (paths == null || paths.isEmpty()) {
            log.trace("delete shared object finished, empty path");
            return;
        }
        synchronized (setSharedObjectMutex) {
            log.trace("delete shared object, paths: {}", paths);
            boolean needSync = false;
            for (List<String> path : paths) {
                if (deleteObject(clusterStarter.nodeIndex, path)) needSync = true;
            }
            if (needSync) {
                var deleteSharedObjectInfo = new DeleteSharedObjectInfo(sharedObjectSeq.get(clusterStarter.nodeIndex), paths);
                redirectFunction.toLeaderFuncConfirmed(targetUrl ->
                                client.getClient(targetUrl).deleteSharedObjectToLeader(clusterBasePath, clusterStarter.nodeIndex, deleteSharedObjectInfo),
                        "delete shared object to leader");
                sharedObjectSeq.put(clusterStarter.nodeIndex, sharedObjectSeq.get(clusterStarter.nodeIndex) + 1);
                log.debug("delete shared object finished, shared-object-sequence: {}", sharedObjectSeq.get(clusterStarter.nodeIndex));
            } else {
                log.debug("delete shared object finished, there is no deleted object");
            }
        }
    }

    Object getItem(int nodeIndex, String... path) {
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
        return item;
    }

    private static class SharedObjectInfo {}

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
}