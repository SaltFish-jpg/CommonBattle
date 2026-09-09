package com.commonbattle.cluster.registry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * 远程注册目录订阅恢复调度器。
 * 网络抖动、中心服重启或代理链路恢复后，周期触发目录增量重放；历史已压缩时由 RemoteServiceRegistry 回退全量快照。
 */
public final class RemoteRegistryRecoveryScheduler implements AutoCloseable, RemoteRegistryRecoveryView {
    private final IntSupplier recovery;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final LongAdder runs = new LongAdder();
    private final LongAdder succeededRuns = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();
    private final LongAdder skippedRuns = new LongAdder();
    private final LongAdder recoveredKinds = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public RemoteRegistryRecoveryScheduler(RemoteServiceRegistry registry, Duration interval) {
        this(registry::recoverSubscriptions, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "remote-registry-recovery");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public RemoteRegistryRecoveryScheduler(
            RemoteServiceRegistry registry,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(registry::recoverSubscriptions, interval, scheduler, false);
    }

    RemoteRegistryRecoveryScheduler(
            IntSupplier recovery,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(recovery, interval, scheduler, false);
    }

    private RemoteRegistryRecoveryScheduler(
            IntSupplier recovery,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
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
        try {
            int recovered = recovery.getAsInt();
            recoveredKinds.add(recovered);
            succeededRuns.increment();
            return recovered;
        } catch (RuntimeException e) {
            failedRuns.increment();
            throw e;
        } finally {
            inFlight.set(false);
        }
    }

    @Override
    public RemoteRegistryRecoveryStats stats() {
        return new RemoteRegistryRecoveryStats(
                runs.sum(),
                succeededRuns.sum(),
                failedRuns.sum(),
                skippedRuns.sum(),
                recoveredKinds.sum(),
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
        try {
            recoverOnce();
        } catch (RuntimeException ignored) {
            // 单次恢复失败只进入统计，调度器继续等待下一轮网络恢复。
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
