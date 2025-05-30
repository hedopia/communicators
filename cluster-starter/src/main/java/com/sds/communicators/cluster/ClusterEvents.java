package com.sds.communicators.cluster;

import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class ClusterEvents {

    final List<Pair<String, Action>> activatedEvents = new ArrayList<>();
    final List<Pair<String, Action>> inactivatedEvents = new ArrayList<>();
    final List<Pair<String, Action>> becomeLeaderEvents = new ArrayList<>();
    final List<Pair<String, Action>> becomeFollowerEvents = new ArrayList<>();
    final List<Pair<String, Consumer<Integer>>> clusterAddedEvents = new ArrayList<>();
    final List<Pair<String, BiConsumer<Integer, Map<String, Object>>>> clusterDeletedEvents = new ArrayList<>();
    final List<Pair<String, Consumer<Integer>>> overwrittenEvents = new ArrayList<>();
    final List<Pair<String, Action>> splitBrainResolvedEvents = new ArrayList<>();

    public ClusterEvents addAll(ClusterEvents clusterEvents) {
        if (clusterEvents != null) {
            activatedEvents.addAll(clusterEvents.activatedEvents);
            inactivatedEvents.addAll(clusterEvents.inactivatedEvents);
            becomeLeaderEvents.addAll(clusterEvents.becomeLeaderEvents);
            becomeFollowerEvents.addAll(clusterEvents.becomeFollowerEvents);
            clusterAddedEvents.addAll(clusterEvents.clusterAddedEvents);
            clusterDeletedEvents.addAll(clusterEvents.clusterDeletedEvents);
            overwrittenEvents.addAll(clusterEvents.overwrittenEvents);
            splitBrainResolvedEvents.addAll(clusterEvents.splitBrainResolvedEvents);
        }
        return this;
    }

    public ClusterEvents activated(String id, Action action) {
        activatedEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents inactivated(String id, Action action) {
        inactivatedEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents becomeLeader(String id, Action action) {
        becomeLeaderEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents becomeFollower(String id, Action action) {
        becomeFollowerEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents clusterAdded(String id, Consumer<Integer> action) {
        clusterAddedEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents clusterDeleted(String id, BiConsumer<Integer, Map<String, Object>> action) {
        clusterDeletedEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents overwritten(String id, Consumer<Integer> action) {
        overwrittenEvents.add(new Pair<>(id, action));
        return this;
    }

    public ClusterEvents splitBrainResolved(String id, Action action) {
        splitBrainResolvedEvents.add(new Pair<>(id, action));
        return this;
    }

    static void fireEvents(List<Pair<String, Action>> events, String eventName) {
        for (var action : events) {
            Schedulers.io().scheduleDirect(() -> {
                try {
                    action.getValue1().run();
                } catch (Throwable e) {
                    log.error("{} event [{}] failed", eventName, action.getValue0(), e);
                }
            });
        }
    }

    static <T> void fireEvents(List<Pair<String, Consumer<T>>> events, T t, String eventName) {
        for (var action : events) {
            Schedulers.io().scheduleDirect(() -> {
                try {
                    action.getValue1().accept(t);
                } catch (Throwable e) {
                    log.error("{} event [{}] failed", eventName, action.getValue0(), e);
                }
            });
        }
    }

    static <T, U> void fireEvents(List<Pair<String, BiConsumer<T, U>>> events, T t, U u, String eventName) {
        for (var action : events) {
            Schedulers.io().scheduleDirect(() -> {
                try {
                    action.getValue1().accept(t, u);
                } catch (Throwable e) {
                    log.error("{} event [{}] failed", eventName, action.getValue0(), e);
                }
            });
        }
    }
}
