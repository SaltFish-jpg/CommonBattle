package com.commonbattle.game.shop;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商店库存 reservation 清理调度器。
 * 单次清理失败只进入统计，不终止后续调度。
 */
public final class ShopStockReservationRetentionScheduler implements AutoCloseable {
    private final ShopStockReservationRetentionService retentionService;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledFuture<?> task;

    public ShopStockReservationRetentionScheduler(
            ShopStockReservationRetentionService retentionService,
            Duration interval
    ) {
        this(retentionService, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shop-stock-reservation-retention");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public ShopStockReservationRetentionScheduler(
            ShopStockReservationRetentionService retentionService,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(retentionService, interval, scheduler, false);
    }

    private ShopStockReservationRetentionScheduler(
            ShopStockReservationRetentionService retentionService,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
        this.interval = positive(interval);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::reapSafely, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public int reapOnce() {
        return retentionService.reap();
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

    private void reapSafely() {
        try {
            retentionService.reap();
        } catch (RuntimeException ignored) {
            // 失败已进入 retentionService 统计，调度线程必须继续运行。
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
