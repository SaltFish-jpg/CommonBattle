package com.commonbattle.game.event;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * owner 关注型事件订阅恢复调度器。
 * 中心事件服重启、网络重连或订阅租约丢失后，周期触发所有 owner 订阅重新续订并按本地 revision 重放。
 */
public final class OwnerActorEventSubscriptionRecoveryScheduler implements AutoCloseable {
    private final List<OwnerActorEventRecoveryTarget> subscriptions;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final LongAdder runs = new LongAdder();
    private final LongAdder succeededRuns = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();
    private final LongAdder skippedRuns = new LongAdder();
    private final LongAdder recoveredSubscriptions = new LongAdder();
    private final LongAdder failedSubscriptions = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public OwnerActorEventSubscriptionRecoveryScheduler(
            Collection<? extends OwnerActorEventRecoveryTarget> subscriptions,
            Duration interval
    ) {
        this(subscriptions, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "owner-actor-event-subscription-recovery");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public OwnerActorEventSubscriptionRecoveryScheduler(
            Collection<? extends OwnerActorEventRecoveryTarget> subscriptions,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(subscriptions, interval, scheduler, false);
    }

    private OwnerActorEventSubscriptionRecoveryScheduler(
            Collection<? extends OwnerActorEventRecoveryTarget> subscriptions,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.subscriptions = List.copyOf(Objects.requireNonNull(subscriptions, "subscriptions"));
        this.interval = positive(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::recoverSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public int recoverOnce() {
        if (!inFlight.compareAndSet(false, true)) {
            skippedRuns.increment();
            return 0;
        }
        runs.increment();
        int recovered = 0;
        int failed = 0;
        try {
            for (OwnerActorEventRecoveryTarget subscription : subscriptions) {
                try {
                    subscription.recoverOwnerSubscriptions();
                    recovered++;
                } catch (RuntimeException e) {
                    failed++;
                }
            }
            recoveredSubscriptions.add(recovered);
            failedSubscriptions.add(failed);
            if (failed == 0) {
                succeededRuns.increment();
            } else {
                failedRuns.increment();
            }
            return recovered;
        } finally {
            inFlight.set(false);
        }
    }

    public OwnerActorEventSubscriptionRecoveryStats stats() {
        return new OwnerActorEventSubscriptionRecoveryStats(
                runs.sum(),
                succeededRuns.sum(),
                failedRuns.sum(),
                skippedRuns.sum(),
                recoveredSubscriptions.sum(),
                failedSubscriptions.sum(),
                inFlight.get()
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

    void recoverSafely() {
        recoverOnce();
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
