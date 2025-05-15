package com.sds.communicators.common;

import io.reactivex.rxjava3.functions.Consumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LoadBalancer {
    private final int shuffledIndexesCnt;
    private final int size;
    private final int threshold;
    private final List<AtomicInteger> failedCnt = new ArrayList<>();
    private final List<AtomicInteger> skippedCnt = new ArrayList<>();
    private final List<List<Integer>> shuffledIndexesList = new ArrayList<>();
    private final AtomicInteger shuffleIdx = new AtomicInteger(0);

    public LoadBalancer(int size, int threshold, int shuffledIndexesCnt) {
        this.size = size;
        this.threshold = threshold;
        for (int i = 0; i < size; i++) {
            failedCnt.add(new AtomicInteger(0));
            skippedCnt.add(new AtomicInteger(0));
        }
        this.shuffledIndexesCnt = shuffledIndexesCnt;
        for (int i = 0; i < shuffledIndexesCnt; i++) {
            var indexes = IntStream.range(0, size).boxed().collect(Collectors.toList());
            Collections.shuffle(indexes);
            shuffledIndexesList.add(indexes);
        }
    }

    public LoadBalancer(int size) {
        this(size, size * 5, size * 100);
    }

    public Throwable run(Consumer<Integer> consumer) {
        var indexes = shuffledIndexesList.get(shuffleIdx.getAndUpdate(c -> c + 1 < shuffledIndexesCnt ? c + 1 : 0));
        Throwable[] result = new Throwable[size];
        Throwable ret = null;
        for (var idx : indexes) {
            if ((ret = result[idx] = run(consumer, idx, true)) == null)
                break;
        }
        if (ret != null) {
            for (var idx : indexes) {
                if (result[idx] instanceof SkipException) {
                    if ((ret = run(consumer, idx, false)) == null)
                        break;
                }
            }
        }
        return ret;
    }

    private Throwable run(Consumer<Integer> consumer, int idx, boolean skip) {
        if (!skip || skippedCnt.get(idx).updateAndGet(c -> failedCnt.get(idx).get() > c ? c + 1 : 0) == 0) {
            try {
                consumer.accept(idx);
                failedCnt.get(idx).set(0);
                skippedCnt.get(idx).set(0);
                return null;
            } catch (Throwable e) {
                failedCnt.get(idx).getAndUpdate(c -> c + 1 < threshold ? c + 1 : c);
                return e;
            }
        } else {
            return new SkipException();
        }
    }

    private static class SkipException extends Exception {}

    public void clear() {
        for (var cnt : failedCnt) cnt.set(0);
        for (var cnt : skippedCnt) cnt.set(0);
    }
}
