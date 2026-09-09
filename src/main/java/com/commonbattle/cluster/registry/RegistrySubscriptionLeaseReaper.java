package com.commonbattle.cluster.registry;

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
 * 中心注册订阅租约清理器。
 * 节点异常退出且没有发送取消订阅时，中心服用它摘除过期订阅关系，避免继续向失效节点推送目录事件。
 */
public final class RegistrySubscriptionLeaseReaper implements AutoCloseable {
    private final CenterRegistryEndpoint endpoint;
    private final Clock clock;
    private final Duration scanInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong expiredSubscriptions = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    public RegistrySubscriptionLeaseReaper(
            CenterRegistryEndpoint endpoint,
            Clock clock,
            Duration scanInterval
    ) {
        this(endpoint, clock, scanInterval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "registry-subscription-lease-reaper");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public RegistrySubscriptionLeaseReaper(
            CenterRegistryEndpoint endpoint,
            Clock clock,
            Duration scanInterval,
            ScheduledExecutorService scheduler
    ) {
        this(endpoint, clock, scanInterval, scheduler, false);
    }

    private RegistrySubscriptionLeaseReaper(
            CenterRegistryEndpoint endpoint,
            Clock clock,
            Duration scanInterval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
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
        int expired = endpoint.expireSubscriptions(clock.instant());
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
