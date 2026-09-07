package com.commonbattle.actor.agent.migration;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 迁移任务保留清理调度器。
 * 负责周期触发清理服务；单次清理失败不会终止后续调度，失败信息由清理服务统计暴露。
 */
public final class AgentMigrationTaskRetentionScheduler implements AutoCloseable {
    private final AgentMigrationTaskRetentionService retentionService;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledFuture<?> task;

    public AgentMigrationTaskRetentionScheduler(
            AgentMigrationTaskRetentionService retentionService,
            Duration interval
    ) {
        this(retentionService, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-migration-task-retention");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public AgentMigrationTaskRetentionScheduler(
            AgentMigrationTaskRetentionService retentionService,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(retentionService, interval, scheduler, false);
    }

    private AgentMigrationTaskRetentionScheduler(
            AgentMigrationTaskRetentionService retentionService,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
        this.interval = positive(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::purgeSafely, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public int purgeOnce() {
        return retentionService.purge();
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

    private void purgeSafely() {
        try {
            retentionService.purge();
        } catch (RuntimeException ignored) {
            // 失败已写入 retentionService 统计，调度线程必须继续保活。
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
