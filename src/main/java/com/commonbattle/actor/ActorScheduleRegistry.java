package com.commonbattle.actor;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带业务键的 Actor 定时调度表。
 * 定时线程只负责按 key 去重、到点投递消息，活动刷新、场景 tick、自动存盘等业务逻辑仍在目标 Actor 邮箱内串行执行。
 */
public final class ActorScheduleRegistry implements ActorScheduleView, AutoCloseable {
    private final ActorSystem system;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Map<ActorScheduleKey, ScheduledJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong scheduledJobs = new AtomicLong();
    private final AtomicLong cancelledJobs = new AtomicLong();
    private final AtomicLong deliveredTimerMessages = new AtomicLong();
    private final AtomicLong rejectedTimerMessages = new AtomicLong();

    public ActorScheduleRegistry(ActorSystem system) {
        this(system, Executors.newSingleThreadScheduledExecutor(new ScheduleThreadFactory()), true);
    }

    public ActorScheduleRegistry(ActorSystem system, ScheduledExecutorService scheduler) {
        this(system, scheduler, false);
    }

    private ActorScheduleRegistry(ActorSystem system, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.system = Objects.requireNonNull(system, "system");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public ActorTimerHandle scheduleOnce(ActorScheduleKey key, ActorRef target, Duration delay, ActorTask task) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        validate(key, target, task);
        ManagedTimerHandle handle = new ManagedTimerHandle(key);
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fireOnce(handle, target, task),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );
        return register(handle.attach(future));
    }

    public ActorTimerHandle scheduleAtFixedRate(
            ActorScheduleKey key,
            ActorRef target,
            Duration initialDelay,
            Duration period,
            ActorTask task
    ) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        validate(key, target, task);
        ManagedTimerHandle handle = new ManagedTimerHandle(key);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> firePeriodic(handle, target, task),
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS
        );
        return register(handle.attach(future));
    }

    public boolean cancel(ActorScheduleKey key) {
        Objects.requireNonNull(key, "key");
        ScheduledJob job = jobs.remove(key);
        if (job == null) {
            return false;
        }
        cancelledJobs.incrementAndGet();
        return job.handle().cancelFuture();
    }

    public ActorScheduleStats stats() {
        return new ActorScheduleStats(
                jobs.size(),
                scheduledJobs.get(),
                cancelledJobs.get(),
                deliveredTimerMessages.get(),
                rejectedTimerMessages.get()
        );
    }

    @Override
    public ActorScheduleStats scheduleStats() {
        return stats();
    }

    private ActorTimerHandle register(ManagedTimerHandle handle) {
        ScheduledJob previous = jobs.put(handle.key(), new ScheduledJob(handle));
        if (previous != null) {
            cancelledJobs.incrementAndGet();
            previous.handle().cancelFuture();
        }
        if (handle.completed()) {
            jobs.remove(handle.key(), new ScheduledJob(handle));
        }
        scheduledJobs.incrementAndGet();
        return handle;
    }

    private void fireOnce(ManagedTimerHandle handle, ActorRef target, ActorTask task) {
        try {
            fire(target, task);
        } finally {
            handle.markCompleted();
            jobs.remove(handle.key(), new ScheduledJob(handle));
        }
    }

    private void firePeriodic(ManagedTimerHandle handle, ActorRef target, ActorTask task) {
        boolean accepted = fire(target, task);
        if (!accepted && !system.isAccepting()) {
            cancel(handle.key());
        }
    }

    private boolean fire(ActorRef target, ActorTask task) {
        boolean accepted = system.trySend(target, ActorTask.withDefaultCategory(ActorTaskCategory.TIMER, task));
        if (accepted) {
            deliveredTimerMessages.incrementAndGet();
        } else {
            rejectedTimerMessages.incrementAndGet();
        }
        return accepted;
    }

    private static void validate(ActorScheduleKey key, ActorRef target, ActorTask task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(task, "task");
    }

    @Override
    public void close() {
        for (ActorScheduleKey key : jobs.keySet()) {
            cancel(key);
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private record ScheduledJob(ManagedTimerHandle handle) {
    }

    private final class ManagedTimerHandle implements ActorTimerHandle {
        private final ActorScheduleKey key;
        private volatile ScheduledFuture<?> future;
        private volatile boolean completed;

        private ManagedTimerHandle(ActorScheduleKey key) {
            this.key = key;
        }

        private ActorScheduleKey key() {
            return key;
        }

        private ManagedTimerHandle attach(ScheduledFuture<?> future) {
            this.future = Objects.requireNonNull(future, "future");
            return this;
        }

        private void markCompleted() {
            completed = true;
        }

        private boolean completed() {
            return completed;
        }

        @Override
        public boolean cancel() {
            return ActorScheduleRegistry.this.cancel(key);
        }

        @Override
        public boolean isCancelled() {
            ScheduledFuture<?> scheduled = future;
            return scheduled != null && scheduled.isCancelled();
        }

        private boolean cancelFuture() {
            ScheduledFuture<?> scheduled = future;
            return scheduled != null && scheduled.cancel(false);
        }
    }

    private static final class ScheduleThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-actor-schedule-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
