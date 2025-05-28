package com.sds.communicators.cluster;

import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.functions.Consumer;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClusterEvents {

    final List<Pair<String, Action>> activatedEvents = new ArrayList<>();
    final List<Pair<String, Action>> inactivatedEvents = new ArrayList<>();
    final List<Pair<String, Action>> becomeLeaderEvents = new ArrayList<>();
    final List<Pair<String, Action>> becomeFollowerEvents = new ArrayList<>();
    final List<Pair<String, Consumer<Integer>>> clusterAddedEvents = new ArrayList<>();
    final List<Pair<String, BiConsumer<Integer, Map<String, Object>>>> clusterDeletedEvents = new ArrayList<>();
    final List<Pair<String, Consumer<Integer>>> overwrittenEvents = new ArrayList<>();

    public ClusterEvents addAll(ClusterEvents clusterEvents) {
        if (clusterEvents != null) {
            activatedEvents.addAll(clusterEvents.activatedEvents);
            inactivatedEvents.addAll(clusterEvents.inactivatedEvents);
            becomeLeaderEvents.addAll(clusterEvents.becomeLeaderEvents);
            becomeFollowerEvents.addAll(clusterEvents.becomeFollowerEvents);
            clusterAddedEvents.addAll(clusterEvents.clusterAddedEvents);
            clusterDeletedEvents.addAll(clusterEvents.clusterDeletedEvents);
            overwrittenEvents.addAll(clusterEvents.overwrittenEvents);
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
}
