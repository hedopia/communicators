package com.sds.communicators.cluster;

import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.reactivex.rxjava3.functions.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
class ClusterRedirectFunction {
    /** how long a lookup's remaining probes may still finish (and keep their connection) before being interrupted */
    private static final long STRAGGLER_GRACE_MILLIS = 500;

    private final ReentrantLock mutex = new ReentrantLock();
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    private final Set<String> nodeTargetUrls;
    private final ClusterInternalClient client;
    private final ClusterStarter clusterStarter;


    /**
     * Runs the consumer against the leader and retries (electing a leader when none answers) until it succeeds.
     * Throws instead of retrying when the failure cannot change on a retry - a deterministic
     * {@link NodeHttpClient.CallException} (request not encodable, 4xx answer) is rethrown - and throws a
     * {@link CancellationException} when the thread is interrupted, leaving the interrupt flag set.
     */
    void toLeaderFuncConfirmed(Consumer<String> consumer, String name) {
        toLeaderFunc(consumer, name, true);
    }

    Throwable toLeaderFunc(Consumer<String> consumer, String name) {
        return toLeaderFunc(consumer, name, false);
    }

    /** retries run as a loop, not recursion - a long leaderless outage must not grow the stack */
    private Throwable toLeaderFunc(Consumer<String> consumer, String name, boolean confirmedExecution) {
        while (true) {
            if (Thread.currentThread().isInterrupted())
                return interrupted(name, confirmedExecution);
            log.trace("execute to-leader-function: {}", name);
            String leaderUrl = clusterStarter.position == Position.LEADER
                    ? clusterStarter.nodeUrl
                    : findFirst(targetUrl -> client.getNodeStatus(targetUrl).getPosition() == Position.LEADER,
                            name + " to leader function");

            if (leaderUrl == null) {
                if (Thread.currentThread().isInterrupted())
                    return interrupted(name, confirmedExecution);
                log.error("({}) leader not found, start to elect leader and retry to leader function", name);
                electLeader();
                if (!sleepHeartbeatInterval())
                    return interrupted(name, confirmedExecution);
                continue;
            }
            try {
                consumer.accept(leaderUrl);
                log.trace("execute to-leader-function finished: {}", name);
                return null;
            } catch (Throwable e) {
                log.error("({}) execute to-leader-function failed (url={})::{}", name, leaderUrl, e.getMessage());
                if (Thread.currentThread().isInterrupted() || findCause(e, InterruptedException.class) != null)
                    return interrupted(name, confirmedExecution);
                if (!confirmedExecution)
                    return e;
                var callException = findCause(e, NodeHttpClient.CallException.class);
                if (callException != null && callException.isDeterministic())
                    throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e.getMessage(), e);
                if (!sleepHeartbeatInterval())
                    return interrupted(name, confirmedExecution);
            }
        }
    }

    private static Throwable interrupted(String name, boolean confirmedExecution) {
        log.warn("({}) to-leader-function interrupted", name);
        Thread.currentThread().interrupt();
        var e = new CancellationException(name + " interrupted");
        if (confirmedExecution)
            throw e;
        return e;
    }

    private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (int depth = 0; e != null && depth < 16; e = e.getCause(), depth++) {
            if (type.isInstance(e))
                return type.cast(e);
        }
        return null;
    }

    /** @return false when interrupted, with the interrupt flag restored */
    private boolean sleepHeartbeatInterval() {
        try {
            Thread.sleep(clusterStarter.heartbeatSendingIntervalMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
                        NodeStatus nodeStatus = client.getNodeStatusForElection(targetUrl);
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
                            client.setToLeader(candidates.get(index));
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
        String indexUrl = indexUrl(nodeIndex, name);

        if (indexUrl == null) {
            log.error("({}) execute to-index-function failed, node-index({}) not found (url={}) ", name, nodeIndex, indexUrl);
            return new Exception("node-index(" + nodeIndex + ") not found");
        } else {
            try {
                consumer.accept(indexUrl);
                log.trace("execute to-index-function finished: {}, node-index: {}", name, nodeIndex);
                return null;
            } catch (Throwable e) {
                log.error("({}) execute to-index-function failed (url={})::{}", name, indexUrl, e.getMessage());
                return e;
            }
        }
    }

    /**
     * the url nodeIndex answers at, found by asking every node for its status (nothing is logged above trace level: the
     * caller decides how much a node not found matters); null when no node answers as nodeIndex
     */
    String indexUrl(int nodeIndex, String name) {
        return clusterStarter.nodeIndex == nodeIndex
                ? clusterStarter.nodeUrl
                : findFirst(targetUrl -> client.getNodeStatus(targetUrl).getNodeIndex() == nodeIndex,
                        name + " to index function");
    }

    /**
     * Probes every node in parallel and returns the first url that matches as soon as it answers, instead of
     * waiting for the slowest (possibly hung) probe; probes still running after a grace period are interrupted.
     * Null when none matched.
     */
    private String findFirst(Predicate<String> matcher, String name) {
        var completionService = new ExecutorCompletionService<String>(executor);
        List<Future<String>> futures = new ArrayList<>();
        for (String targetUrl : new ArrayList<>(nodeTargetUrls))
            futures.add(completionService.submit(() -> {
                try {
                    return matcher.test(targetUrl) ? targetUrl : null;
                } catch (Throwable e) {
                    log.trace("({}) check status (url={}) failed::{}", name, targetUrl, e.getMessage());
                    return null;
                }
            }));

        try {
            for (int i = 0; i < futures.size(); i++) {
                String found = completionService.take().get();
                if (found != null)
                    return found;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.trace("({}) check status failed", name, e);
        } finally {
            // not right away: interrupting HttpClient.send closes the connection, so every lookup would drop the pooled
            // connection of each healthy peer that answers a little later than the match
            if (futures.stream().anyMatch(future -> !future.isDone()))
                CompletableFuture.delayedExecutor(STRAGGLER_GRACE_MILLIS, TimeUnit.MILLISECONDS, executor)
                        .execute(() -> futures.forEach(future -> future.cancel(true)));
        }
        return null;
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.trace("parallel-execute interrupted", e);
        } catch (Exception e) {
            log.trace("parallel-execute interrupted", e);
        }
    }
}
