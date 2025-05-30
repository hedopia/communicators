package com.sds.communicators.driver;

import com.sds.communicators.common.struct.Device;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DriverEvents {
    final List<Pair<String, Consumer<Device>>> deviceAddedEvents = new ArrayList<>();
    final List<Pair<String, Consumer<Device>>> deviceDeletedEvents = new ArrayList<>();

    public static DriverEvents create() {
        return new DriverEvents();
    }

    public DriverEvents addAll(DriverEvents addEvents) {
        if (addEvents != null) {
            deviceAddedEvents.addAll(addEvents.deviceAddedEvents);
            deviceDeletedEvents.addAll(addEvents.deviceDeletedEvents);
        }
        return this;
    }

    public DriverEvents deviceAdded(String id, Consumer<Device> action) {
        deviceAddedEvents.add(new Pair<>(id, action));
        return this;
    }

    public DriverEvents deviceDeleted(String id, Consumer<Device> action) {
        deviceDeletedEvents.add(new Pair<>(id, action));
        return this;
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
}
