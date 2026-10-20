package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTask;
import com.commonbattle.actor.ActorTaskCategory;

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
    private final ActorSystem actors;
    private final ActorRef maintenanceActor;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile ScheduledFuture<?> task;

    public ShopStockReservationRetentionScheduler(
            ShopStockReservationRetentionService retentionService,
            Duration interval,
            ActorSystem actors,
            ActorRef maintenanceActor
    ) {
        this(retentionService, interval, actors, maintenanceActor,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shop-stock-reservation-retention");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public ShopStockReservationRetentionScheduler(
            ShopStockReservationRetentionService retentionService,
            Duration interval,
            ActorSystem actors,
            ActorRef maintenanceActor,
            ScheduledExecutorService scheduler
    ) {
        this(retentionService, interval, actors, maintenanceActor, scheduler, false);
    }

    private ShopStockReservationRetentionScheduler(
            ShopStockReservationRetentionService retentionService,
            Duration interval,
            ActorSystem actors,
            ActorRef maintenanceActor,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
        this.interval = positive(interval);
        this.actors = Objects.requireNonNull(actors, "actors");
        this.maintenanceActor = Objects.requireNonNull(maintenanceActor, "maintenanceActor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(this::enqueueSafely, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    public boolean enqueueOnce() {
        boolean accepted = actors.trySend(maintenanceActor, ActorTask.categorized(
                ActorTaskCategory.TIMER,
                ignored -> runReapSafely()
        ));
        if (!accepted) {
            retentionService.rejected();
        }
        return accepted;
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

    private void enqueueSafely() {
        try {
            enqueueOnce();
        } catch (RuntimeException ignored) {
            retentionService.rejected();
        }
    }

    private void runReapSafely() {
        try {
            retentionService.reap();
        } catch (RuntimeException ignored) {
            // 失败已进入 retentionService 统计，Actor worker 必须继续处理后续消息。
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
