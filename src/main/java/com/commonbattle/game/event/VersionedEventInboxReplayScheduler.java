package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * durable inbox 的统一补投调度器。
 * 每次只要求订阅器扫描自身 topic，单个 topic 失败不会中断其它事件流。
 */
public final class VersionedEventInboxReplayScheduler implements AutoCloseable {
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final CopyOnWriteArrayList<ActorMailboxEventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final LongAdder runs = new LongAdder();
    private final LongAdder subscriberRuns = new LongAdder();
    private final LongAdder failedSubscriberRuns = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public VersionedEventInboxReplayScheduler(Duration interval) {
        this(interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "versioned-event-inbox-replay");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public VersionedEventInboxReplayScheduler(Duration interval, ScheduledExecutorService scheduler) {
        this(interval, scheduler, false);
    }

    private VersionedEventInboxReplayScheduler(
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.interval = positive(interval);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void register(ActorMailboxEventSubscriber subscriber) {
        subscribers.addIfAbsent(Objects.requireNonNull(subscriber, "subscriber"));
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::replayOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void replayOnce() {
        runs.increment();
        for (ActorMailboxEventSubscriber subscriber : subscribers) {
            subscriberRuns.increment();
            try {
                subscriber.replayPending();
            } catch (RuntimeException ignored) {
                failedSubscriberRuns.increment();
            }
        }
    }

    public VersionedEventInboxReplaySchedulerStats stats() {
        return new VersionedEventInboxReplaySchedulerStats(
                runs.sum(), subscriberRuns.sum(), failedSubscriberRuns.sum(), subscribers.size()
        );
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        started.set(false);
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static Duration positive(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        return interval;
    }
}
