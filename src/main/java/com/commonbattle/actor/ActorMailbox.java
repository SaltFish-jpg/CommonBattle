package com.commonbattle.actor;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class ActorMailbox {
    private final ActorRef ref;
    private final Queue<ActorTask> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicInteger size = new AtomicInteger();

    ActorMailbox(ActorRef ref) {
        this.ref = ref;
    }

    ActorRef ref() {
        return ref;
    }

    boolean enqueue(ActorTask task, int capacity) {
        while (true) {
            int current = size.get();
            if (current >= capacity) {
                return false;
            }
            if (size.compareAndSet(current, current + 1)) {
                tasks.add(task);
                return true;
            }
        }
    }

    void enqueue(ActorTask task) {
        size.incrementAndGet();
        tasks.add(task);
    }

    boolean trySchedule() {
        return scheduled.compareAndSet(false, true);
    }

    void markIdle() {
        scheduled.set(false);
    }

    boolean hasTasks() {
        return !tasks.isEmpty();
    }

    ActorTask poll() {
        ActorTask task = tasks.poll();
        if (task != null) {
            size.decrementAndGet();
        }
        return task;
    }

    int size() {
        return size.get();
    }
}
