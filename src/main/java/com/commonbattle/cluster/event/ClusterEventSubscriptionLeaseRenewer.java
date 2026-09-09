package com.commonbattle.cluster.event;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务事件订阅租约续订器。
 * 它只刷新中心事件订阅租约，不改变本地订阅引用计数，避免周期续订把业务关注关系放大。
 */
public final class ClusterEventSubscriptionLeaseRenewer implements AutoCloseable {
    private final ClusterVersionedEventBus bus;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong renewedSubscriptions = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    public ClusterEventSubscriptionLeaseRenewer(ClusterVersionedEventBus bus, Duration interval) {
        this(bus, interval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cluster-event-subscription-lease-renewer");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public ClusterEventSubscriptionLeaseRenewer(
            ClusterVersionedEventBus bus,
            Duration interval,
            ScheduledExecutorService scheduler
    ) {
        this(bus, interval, scheduler, false);
    }

    private ClusterEventSubscriptionLeaseRenewer(
            ClusterVersionedEventBus bus,
            Duration interval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.interval = positive(interval, "interval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        task = scheduler.scheduleAtFixedRate(
                this::renewSafely,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public int renewOnce() {
        runs.incrementAndGet();
        try {
            int renewed = bus.renewRemoteSubscriptions();
            renewedSubscriptions.addAndGet(renewed);
            return renewed;
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            throw e;
        }
    }

    public long runs() {
        return runs.get();
    }

    public long renewedSubscriptions() {
        return renewedSubscriptions.get();
    }

    public long failures() {
        return failures.get();
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

    private void renewSafely() {
        try {
            renewOnce();
        } catch (RuntimeException ignored) {
            // 续订失败只进入统计，下一轮恢复网络后继续刷新。
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
