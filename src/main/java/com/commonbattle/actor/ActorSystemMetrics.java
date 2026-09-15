package com.commonbattle.actor;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class ActorSystemMetrics {
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final LongAdder failedTasks = new LongAdder();
    private final LongAdder rejectedTasks = new LongAdder();
    private final LongAdder droppedTasks = new LongAdder();
    private final LongAdder slowTasks = new LongAdder();
    private final EnumMap<ActorTaskCategory, LongAdder> rejectedTasksByCategory =
            new EnumMap<>(ActorTaskCategory.class);
    private final EnumMap<ActorTaskCategory, LongAdder> droppedTasksByCategory =
            new EnumMap<>(ActorTaskCategory.class);
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicInteger runningMailboxes = new AtomicInteger();
    private final AtomicInteger peakQueuedTasks = new AtomicInteger();
    private final AtomicInteger peakRunningMailboxes = new AtomicInteger();
    private final AtomicLong slowestTaskNanos = new AtomicLong();
    private volatile String slowestTaskActorId = "";
    private volatile ActorTaskCategory slowestTaskCategory = ActorTaskCategory.DEFAULT;

    ActorSystemMetrics() {
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            rejectedTasksByCategory.put(category, new LongAdder());
            droppedTasksByCategory.put(category, new LongAdder());
        }
    }

    void taskSubmitted() {
        submittedTasks.increment();
        updatePeak(peakQueuedTasks, queuedTasks.incrementAndGet());
    }

    void taskPolled() {
        queuedTasks.decrementAndGet();
    }

    void taskCompleted() {
        completedTasks.increment();
    }

    void taskFailed() {
        failedTasks.increment();
    }

    void taskExecuted(ActorRef actor, ActorTaskCategory category, long elapsedNanos, java.time.Duration slowTaskThreshold) {
        if (!slowTaskThreshold.isZero() && elapsedNanos >= slowTaskThreshold.toNanos()) {
            slowTasks.increment();
        }
        long current;
        do {
            current = slowestTaskNanos.get();
            if (elapsedNanos <= current) {
                return;
            }
        } while (!slowestTaskNanos.compareAndSet(current, elapsedNanos));
        slowestTaskActorId = actor.id();
        slowestTaskCategory = category;
    }

    void taskRejected(ActorTaskCategory category) {
        rejectedTasks.increment();
        rejectedTasksByCategory.get(category).increment();
    }

    void taskDropped(ActorTaskCategory category) {
        droppedTasks.increment();
        droppedTasksByCategory.get(category).increment();
        queuedTasks.decrementAndGet();
    }

    void mailboxScheduled() {
        updatePeak(peakRunningMailboxes, runningMailboxes.incrementAndGet());
    }

    void mailboxIdle() {
        runningMailboxes.decrementAndGet();
    }

    ActorSystemStats snapshot(
            int activeMailboxes,
            int largestMailboxQueuedTasks,
            String largestMailboxActorId,
            Map<ActorTaskCategory, Integer> queuedTasksByCategory
    ) {
        return new ActorSystemStats(
                submittedTasks.sum(),
                completedTasks.sum(),
                failedTasks.sum(),
                rejectedTasks.sum(),
                droppedTasks.sum(),
                queuedTasks.get(),
                runningMailboxes.get(),
                activeMailboxes,
                largestMailboxQueuedTasks,
                largestMailboxActorId,
                peakQueuedTasks.get(),
                peakRunningMailboxes.get(),
                slowTasks.sum(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(slowestTaskNanos.get()),
                slowestTaskActorId,
                slowestTaskCategory,
                Map.copyOf(queuedTasksByCategory),
                sumByCategory(rejectedTasksByCategory),
                sumByCategory(droppedTasksByCategory)
        );
    }

    private static Map<ActorTaskCategory, Long> sumByCategory(Map<ActorTaskCategory, LongAdder> values) {
        EnumMap<ActorTaskCategory, Long> snapshot = new EnumMap<>(ActorTaskCategory.class);
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            snapshot.put(category, values.get(category).sum());
        }
        return Map.copyOf(snapshot);
    }

    private static void updatePeak(AtomicInteger peak, int value) {
        peak.accumulateAndGet(value, Math::max);
    }
}
