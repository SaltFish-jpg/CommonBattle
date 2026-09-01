package com.commonbattle.actor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

final class ActorSystemMetrics {
    private final LongAdder submittedTasks = new LongAdder();
    private final LongAdder completedTasks = new LongAdder();
    private final LongAdder failedTasks = new LongAdder();
    private final LongAdder rejectedTasks = new LongAdder();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicInteger runningMailboxes = new AtomicInteger();

    void taskSubmitted() {
        submittedTasks.increment();
        queuedTasks.incrementAndGet();
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

    void taskRejected() {
        rejectedTasks.increment();
    }

    void mailboxScheduled() {
        runningMailboxes.incrementAndGet();
    }

    void mailboxIdle() {
        runningMailboxes.decrementAndGet();
    }

    ActorSystemStats snapshot() {
        return new ActorSystemStats(
                submittedTasks.sum(),
                completedTasks.sum(),
                failedTasks.sum(),
                rejectedTasks.sum(),
                queuedTasks.get(),
                runningMailboxes.get()
        );
    }
}
