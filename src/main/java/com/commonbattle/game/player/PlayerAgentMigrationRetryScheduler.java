package com.commonbattle.game.player;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家 Agent 源侧迁移重试调度器。
 * 只触发 retry service 重新提交迁移，真正的快照打包和状态切换仍在玩家 owner 邮箱内完成。
 */
public final class PlayerAgentMigrationRetryScheduler implements AutoCloseable {
    private final PlayerAgentMigrationRetryService retries;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledFuture<?> future;

    public PlayerAgentMigrationRetryScheduler(PlayerAgentMigrationRetryService retries, Duration interval) {
        this.retries = Objects.requireNonNull(retries, "retries");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new RetryThreadFactory());
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        future = scheduler.scheduleAtFixedRate(
                retries::retryPending,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        scheduler.shutdownNow();
    }

    private static final class RetryThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-player-migration-retry-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
