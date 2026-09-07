package com.commonbattle.actor.agent.migration;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Agent 迁移恢复调度器。
 * 周期扫描 PREPARED/MOVED 任务并交给恢复服务处理，单次扫描异常不会终止后续恢复调度。
 */
public final class AgentMigrationRecoveryScheduler implements AutoCloseable {
    private final AgentMigrationRecoveryService recoveryService;
    private final AgentMigrationResultCallback callback;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final LongAdder runs = new LongAdder();
    private final LongAdder claimedTasks = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();
    private volatile ScheduledFuture<?> task;

    public AgentMigrationRecoveryScheduler(
            AgentMigrationRecoveryService recoveryService,
            AgentMigrationResultCallback callback,
            Duration interval
    ) {
        this(recoveryService, callback, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-migration-recovery");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public AgentMigrationRecoveryScheduler(
            AgentMigrationRecoveryService recoveryService,
            AgentMigrationResultCallback callback,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(recoveryService, callback, interval, scheduler, false);
    }

    private AgentMigrationRecoveryScheduler(
            AgentMigrationRecoveryService recoveryService,
            AgentMigrationResultCallback callback,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
        this.callback = Objects.requireNonNull(callback, "callback");
        this.interval = positive(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::recoverSafely, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public int recoverOnce() {
        runs.increment();
        try {
            int claimed = recoveryService.recoverPending(callback);
            claimedTasks.add(claimed);
            return claimed;
        } catch (RuntimeException e) {
            failedRuns.increment();
            throw e;
        }
    }

    public AgentMigrationRecoverySchedulerStats stats() {
        return new AgentMigrationRecoverySchedulerStats(runs.sum(), claimedTasks.sum(), failedRuns.sum());
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
            // 让定时恢复持续运行；具体失败由任务存储或恢复链路的健康指标暴露。
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
