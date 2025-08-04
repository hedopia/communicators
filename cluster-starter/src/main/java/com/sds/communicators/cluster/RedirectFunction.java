package com.sds.communicators.cluster;

import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.reactivex.rxjava3.functions.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
class RedirectFunction {
    private final ReentrantLock mutex = new ReentrantLock();
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    private final Set<String> nodeTargetUrls;
    private final ClusterClient client;
    private final ClusterStarter clusterStarter;
    private final String clusterBasePath;


    void toLeaderFuncConfirmed(Consumer<String> consumer, String name) {
        toLeaderFunc(consumer, name, true, true);
    }

    Throwable toLeaderFunc(Consumer<String> consumer, String name) {
        return toLeaderFunc(consumer, name, false, true);
    }

    private Throwable toLeaderFunc(Consumer<String> consumer, String name, boolean confirmedExecution, boolean isFirst) {
        log.trace("execute to-leader-function: {}", name);
        if (!isFirst) {
            try {
                Thread.sleep(clusterStarter.heartbeatSendingIntervalMillis);
            } catch (InterruptedException ignored) {}
        }
        AtomicReference<String> leaderUrl = new AtomicReference<>(null);
        if (clusterStarter.position == Position.LEADER)
            leaderUrl.set(clusterStarter.nodeUrl);
        else
            parallelExecute(nodeTargetUrls, targetUrl -> {
                try {
                    if (client.getClient(targetUrl, ClusterClient.ClusterClientApi.class).getNodeStatus(clusterBasePath).getPosition() == Position.LEADER)
                        leaderUrl.set(targetUrl);
                } catch (Exception e) {
                    log.trace("({}) check status (url={}) failed to leader function::{}", name, targetUrl, e.getMessage());
                }
            });

        if (leaderUrl.get() == null) {
            log.error("({}) leader not found, start to elect leader and retry to leader function", name);
            electLeader();
            return toLeaderFunc(consumer, name, confirmedExecution, false);
        } else {
            try {
                consumer.accept(leaderUrl.get());
                log.trace("execute to-leader-function finished: {}", name);
                return null;
            } catch (Throwable e) {
                log.error("({}) execute to-leader-function failed (url={})::{}", name, leaderUrl.get(), e.getMessage());
                if (confirmedExecution)
                    return toLeaderFunc(consumer, name, true, false);
                else
                    return e;
            }
        }
    }

    void electLeader() {
        log.trace("try to elect leader");
        if (mutex.tryLock()) {
            AtomicBoolean existLeader = new AtomicBoolean(false);
            Map<Integer, String> candidates = new ConcurrentHashMap<>();
            if (clusterStarter.position == Position.LEADER) {
                existLeader.set(true);
            } else {
                candidates.putIfAbsent(clusterStarter.nodeIndex, clusterStarter.nodeUrl);
                parallelExecute(nodeTargetUrls, targetUrl -> {
                    try {
                        NodeStatus nodeStatus = client.getClient(targetUrl, ClusterClient.ClusterClientApi.class).getNodeStatus(clusterBasePath);
                        if (nodeStatus.getPosition() == Position.LEADER)
                            existLeader.set(true);
                        else
                            candidates.putIfAbsent(nodeStatus.getNodeIndex(), targetUrl);
                    } catch (Exception e) {
                        log.trace("check status (url={}) failed to elect leader::{}", targetUrl, e.getMessage());
                    }
                });
            }

            if (!existLeader.get()) {
                List<Integer> sortedCandidates = candidates.keySet().stream().sorted().collect(Collectors.toList());
                if (sortedCandidates.isEmpty()) {
                    log.error("candidates not found, elect leader failed");
                } else {
                    for (int index : sortedCandidates) {
                        try {
                            log.info("set to leader (index={}, url={})", index, candidates.get(index));
                            client.getClient(candidates.get(index), ClusterClient.ClusterClientApi.class).setToLeader(clusterBasePath);
                            break;
                        } catch (Exception e) {
                            log.error("set to leader (index={}, url={}) failed::{}", index, candidates.get(index), e.getMessage());
                        }
                    }
                }
            }
            mutex.unlock();
        } else {
            log.debug("elect leader ignored, because of already processing");
        }
    }

    Throwable toIndexFunc(int nodeIndex, Consumer<String> consumer, String name) {
        log.trace("execute to-index-function: {}, node-index: {}", name, nodeIndex);
        AtomicReference<String> indexUrl = new AtomicReference<>(null);
        if (clusterStarter.nodeIndex == nodeIndex)
            indexUrl.set(clusterStarter.nodeUrl);
        else
            parallelExecute(nodeTargetUrls, targetUrl -> {
                try {
                    if (client.getClient(targetUrl, ClusterClient.ClusterClientApi.class).getNodeStatus(clusterBasePath).getNodeIndex() == nodeIndex)
                        indexUrl.set(targetUrl);
                } catch (Exception e) {
                    log.trace("({}) check status (url={}) failed to index function::{}", name, targetUrl, e.getMessage());
                }
            });

        if (indexUrl.get() == null) {
            log.error("({}) execute to-index-function failed, node-index({}) not found (url={}) ", name, nodeIndex, indexUrl.get());
            return new Exception("node-index(" + nodeIndex + ") not found");
        } else {
            try {
                consumer.accept(indexUrl.get());
                log.trace("execute to-index-function finished: {}, node-index: {}", name, nodeIndex);
                return null;
            } catch (Throwable e) {
                log.error("({}) execute to-index-function failed (url={})::{}", name, indexUrl.get(), e.getMessage());
                return e;
            }
        }
    }

    void toAllFunc(Consumer<String> consumer, String name) {
        log.trace("execute to-all-function: {}", name);
        parallelExecute(nodeTargetUrls, targetUrl -> {
            try {
                consumer.accept(targetUrl);
            } catch (Throwable e) {
                log.error("({}) execute to-all-function failed (url={})::{}", name, targetUrl, e.getMessage());
            }
        });

        log.trace("execute to-all-function finished: {}", name);
    }

    <T> void parallelExecute(Collection<T> collection, Consumer<T> consumer) {
        List<Future<?>> futures = new ArrayList<>();
        for (T item : new ArrayList<>(collection))
            futures.add(executor.submit(() -> {
                try {
                    consumer.accept(item);
                } catch (Throwable e) {
                    log.trace("parallel-execute for {} failed", item, e);
                }
            }));

        try {
            for (Future<?> future : futures)
                future.get();
        } catch (Exception e) {
            log.trace("parallel-execute interrupted", e);
        }
    }
}
