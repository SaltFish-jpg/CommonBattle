package com.commonbattle.game.event;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 服务内共享的 owner 投影修复分发器。
 * 多个 topic 的修复调度器注册进来后，由这里统一按到期时间、优先级和积压量仲裁执行顺序。
 */
public final class OwnerEventRepairDispatcher implements OwnerEventRepairDispatcherView, AutoCloseable {
    public static final Duration DEFAULT_TICK_INTERVAL = Duration.ofSeconds(1);
    public static final int DEFAULT_MAX_DRAINS_PER_TICK = 4;

    private final Duration tickInterval;
    private final int maxDrainsPerTick;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final LongSupplier nanoTime;
    private final List<Entry> entries = new ArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final LongAdder drainAttempts = new LongAdder();
    private final LongAdder selectedSchedulers = new LongAdder();
    private final LongAdder drainedOwners = new LongAdder();
    private final LongAdder failedSchedulerRuns = new LongAdder();
    private final LongAdder limitedRuns = new LongAdder();
    private final LongAdder emptyRuns = new LongAdder();
    private final LongAdder skippedRuns = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public OwnerEventRepairDispatcher(Duration tickInterval, int maxDrainsPerTick) {
        this(tickInterval, maxDrainsPerTick, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "owner-event-repair-dispatcher");
            thread.setDaemon(true);
            return thread;
        }), true, System::nanoTime);
    }

    public OwnerEventRepairDispatcher(
            Duration tickInterval,
            int maxDrainsPerTick,
            ScheduledExecutorService scheduler
    ) {
        this(tickInterval, maxDrainsPerTick, scheduler, false, System::nanoTime);
    }

    OwnerEventRepairDispatcher(Duration tickInterval, int maxDrainsPerTick, LongSupplier nanoTime) {
        this(tickInterval, maxDrainsPerTick, Executors.newSingleThreadScheduledExecutor(), true, nanoTime);
    }

    private OwnerEventRepairDispatcher(
            Duration tickInterval,
            int maxDrainsPerTick,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler,
            LongSupplier nanoTime
    ) {
        this.tickInterval = positive(tickInterval, "tickInterval");
        if (maxDrainsPerTick <= 0) {
            throw new IllegalArgumentException("maxDrainsPerTick must be positive");
        }
        this.maxDrainsPerTick = maxDrainsPerTick;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void register(OwnerEventRepairScheduler repairScheduler, Duration interval) {
        Objects.requireNonNull(repairScheduler, "repairScheduler");
        Duration positiveInterval = positive(interval, "interval");
        synchronized (entries) {
            entries.add(new Entry(repairScheduler, positiveInterval.toNanos(), safeAdd(nanoTime.getAsLong(),
                    positiveInterval.toNanos())));
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::drainSafely,
                tickInterval.toMillis(),
                tickInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public int drainOnce() {
        drainAttempts.increment();
        if (!inFlight.compareAndSet(false, true)) {
            skippedRuns.increment();
            return 0;
        }
        int drainedOwners = 0;
        try {
            for (int i = 0; i < maxDrainsPerTick; i++) {
                Entry entry = selectNext(nanoTime.getAsLong());
                if (entry == null) {
                    if (i == 0) {
                        emptyRuns.increment();
                    }
                    return drainedOwners;
                }
                entry.markDispatched(nanoTime.getAsLong());
                selectedSchedulers.increment();
                try {
                    int drained = entry.repairScheduler().drainOnce();
                    drainedOwners += drained;
                    this.drainedOwners.add(drained);
                } catch (RuntimeException ignored) {
                    failedSchedulerRuns.increment();
                    // 单个 topic 修复失败已由 scheduler 计数并重入队，这里继续给其他 topic 机会。
                }
            }
            if (selectNext(nanoTime.getAsLong()) != null) {
                limitedRuns.increment();
            }
            return drainedOwners;
        } finally {
            inFlight.set(false);
        }
    }

    @Override
    public OwnerEventRepairDispatcherStats repairDispatcherStats() {
        long nowNanos = nanoTime.getAsLong();
        synchronized (entries) {
            int dueSchedulers = 0;
            int pendingSchedulers = 0;
            int eligibleSchedulers = 0;
            int highestPendingPriority = 0;
            for (Entry entry : entries) {
                OwnerEventRepairSchedulerStats stats = entry.repairScheduler().repairSchedulerStats();
                boolean due = entry.due(nowNanos);
                boolean pending = stats.pendingOwners() > 0;
                boolean ready = pending && !stats.backoffActive();
                if (due) {
                    dueSchedulers++;
                }
                if (pending) {
                    pendingSchedulers++;
                    highestPendingPriority = Math.max(highestPendingPriority, stats.highestPendingPriority());
                }
                if (due && ready) {
                    eligibleSchedulers++;
                }
            }
            return new OwnerEventRepairDispatcherStats(
                    entries.size(),
                    dueSchedulers,
                    pendingSchedulers,
                    eligibleSchedulers,
                    highestPendingPriority,
                    maxDrainsPerTick,
                    tickInterval.toMillis(),
                    drainAttempts.sum(),
                    selectedSchedulers.sum(),
                    this.drainedOwners.sum(),
                    failedSchedulerRuns.sum(),
                    limitedRuns.sum(),
                    emptyRuns.sum(),
                    skippedRuns.sum(),
                    inFlight.get()
            );
        }
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

    private void drainSafely() {
        try {
            drainOnce();
        } catch (RuntimeException ignored) {
        }
    }

    private Entry selectNext(long nowNanos) {
        synchronized (entries) {
            return entries.stream()
                    .filter(entry -> entry.due(nowNanos))
                    .filter(OwnerEventRepairDispatcher::readyToDrain)
                    .max(Comparator
                            .comparingInt((Entry entry) -> entry.repairScheduler()
                                    .repairSchedulerStats()
                                    .highestPendingPriority())
                            .thenComparingInt(entry -> entry.repairScheduler()
                                    .repairSchedulerStats()
                                    .pendingOwners())
                            .thenComparingLong(entry -> -entry.nextDueNanos()))
                    .orElse(null);
        }
    }

    private static boolean readyToDrain(Entry entry) {
        OwnerEventRepairSchedulerStats stats = entry.repairScheduler().repairSchedulerStats();
        return stats.pendingOwners() > 0 && !stats.backoffActive();
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static long safeAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static final class Entry {
        private final OwnerEventRepairScheduler repairScheduler;
        private final long intervalNanos;
        private long nextDueNanos;

        private Entry(OwnerEventRepairScheduler repairScheduler, long intervalNanos, long nextDueNanos) {
            this.repairScheduler = repairScheduler;
            this.intervalNanos = intervalNanos;
            this.nextDueNanos = nextDueNanos;
        }

        private OwnerEventRepairScheduler repairScheduler() {
            return repairScheduler;
        }

        private boolean due(long nowNanos) {
            return nowNanos >= nextDueNanos;
        }

        private long nextDueNanos() {
            return nextDueNanos;
        }

        private void markDispatched(long nowNanos) {
            nextDueNanos = safeAdd(nowNanos, intervalNanos);
        }
    }
}
