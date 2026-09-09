package com.commonbattle.cluster.event;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 中心事件订阅租约清理器。
 * 订阅服务异常退出时，它负责清理过期 topic/owner 订阅，避免中心持续推送到失效节点。
 */
public final class ClusterEventSubscriptionLeaseReaper implements AutoCloseable {
    private final ClusterEventCenter center;
    private final Clock clock;
    private final Duration scanInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong expiredSubscriptions = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    public ClusterEventSubscriptionLeaseReaper(ClusterEventCenter center, Clock clock, Duration scanInterval) {
        this(center, clock, scanInterval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cluster-event-subscription-lease-reaper");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public ClusterEventSubscriptionLeaseReaper(
            ClusterEventCenter center,
            Clock clock,
            Duration scanInterval,
            ScheduledExecutorService scheduler
    ) {
        this(center, clock, scanInterval, scheduler, false);
    }

    private ClusterEventSubscriptionLeaseReaper(
            ClusterEventCenter center,
            Clock clock,
            Duration scanInterval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.center = Objects.requireNonNull(center, "center");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scanInterval = positive(scanInterval, "scanInterval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::expireOnce,
                scanInterval.toMillis(),
                scanInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public int expireOnce() {
        int expired = center.expireSubscriptions(clock.instant());
        expiredSubscriptions.addAndGet(expired);
        return expired;
    }

    public long expiredSubscriptions() {
        return expiredSubscriptions.get();
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

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
