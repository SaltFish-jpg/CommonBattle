package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 版本事件 outbox 后台补发调度器。
 * 周期调用 reliablePublisher.replayPending，单次异常只记录统计，不终止后续补发。
 */
public final class VersionedEventOutboxReplayScheduler implements AutoCloseable {
    private final ReliableVersionedEventPublisher reliablePublisher;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final LongAdder runs = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public VersionedEventOutboxReplayScheduler(ReliableVersionedEventPublisher reliablePublisher, Duration interval) {
        this(reliablePublisher, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "versioned-event-outbox-replay");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public VersionedEventOutboxReplayScheduler(
            ReliableVersionedEventPublisher reliablePublisher,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(reliablePublisher, interval, scheduler, false);
    }

    private VersionedEventOutboxReplayScheduler(
            ReliableVersionedEventPublisher reliablePublisher,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.reliablePublisher = Objects.requireNonNull(reliablePublisher, "reliablePublisher");
        this.interval = positive(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::replaySafely, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public void replayOnce() {
        runs.increment();
        reliablePublisher.replayPending();
    }

    public VersionedEventOutboxReplaySchedulerStats stats() {
        return new VersionedEventOutboxReplaySchedulerStats(runs.sum(), failedRuns.sum());
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

    void replaySafely() {
        try {
            replayOnce();
        } catch (RuntimeException ignored) {
            failedRuns.increment();
        }
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
