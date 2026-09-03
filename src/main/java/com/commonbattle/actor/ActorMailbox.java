package com.commonbattle.actor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class ActorMailbox {
    private final ActorRef ref;
    private final Queue<ActorTask> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicInteger size = new AtomicInteger();
    private final EnumMap<ActorTaskCategory, AtomicInteger> categorySizes = new EnumMap<>(ActorTaskCategory.class);

    ActorMailbox(ActorRef ref) {
        this.ref = ref;
        for (ActorTaskCategory category : ActorTaskCategory.values()) {
            categorySizes.put(category, new AtomicInteger());
        }
    }

    ActorRef ref() {
        return ref;
    }

    ActorMailboxEnqueueResult enqueue(ActorTask task, ActorSystemConfig config) {
        ActorTaskCategory category = categoryOf(task);
        int categoryCapacity = config.capacityFor(category);
        if (categoryCapacity < config.mailboxCapacity()) {
            if (!tryAcquireCategorySlot(category, categoryCapacity)) {
                return ActorMailboxEnqueueResult.rejected(DeadLetterReason.MAILBOX_CATEGORY_FULL);
            }
        } else {
            categorySizes.get(category).incrementAndGet();
        }
        while (true) {
            int current = size.get();
            if (current >= config.mailboxCapacity()) {
                if (config.overflowStrategy() == ActorOverflowStrategy.DROP_OLDEST) {
                    ActorTask dropped = tasks.poll();
                    if (dropped == null) {
                        releaseCategorySlot(category);
                        return ActorMailboxEnqueueResult.rejected(DeadLetterReason.MAILBOX_FULL);
                    }
                    releaseCategorySlot(categoryOf(dropped));
                    tasks.add(task);
                    return ActorMailboxEnqueueResult.acceptedWithDrop(dropped);
                }
                releaseCategorySlot(category);
                return ActorMailboxEnqueueResult.rejected(DeadLetterReason.MAILBOX_FULL);
            }
            if (size.compareAndSet(current, current + 1)) {
                tasks.add(task);
                return ActorMailboxEnqueueResult.acceptedResult();
            }
        }
    }

    void enqueue(ActorTask task) {
        size.incrementAndGet();
        categorySizes.get(categoryOf(task)).incrementAndGet();
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
            releaseCategorySlot(categoryOf(task));
        }
        return task;
    }

    int size() {
        return size.get();
    }

    Map<ActorTaskCategory, Integer> categorySizes() {
        EnumMap<ActorTaskCategory, Integer> snapshot = new EnumMap<>(ActorTaskCategory.class);
        for (Map.Entry<ActorTaskCategory, AtomicInteger> entry : categorySizes.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return Map.copyOf(snapshot);
    }

    private boolean tryAcquireCategorySlot(ActorTaskCategory category, int capacity) {
        AtomicInteger counter = categorySizes.get(category);
        while (true) {
            int current = counter.get();
            if (current >= capacity) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseCategorySlot(ActorTaskCategory category) {
        categorySizes.get(category).decrementAndGet();
    }

    private static ActorTaskCategory categoryOf(ActorTask task) {
        ActorTaskCategory category = task.category();
        return category == null ? ActorTaskCategory.DEFAULT : category;
    }
}
