package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceRegistry;

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
 * 注册中心租约清理器。
 * 中心服周期扫描过期服务并发布取消注册事件，让各节点本地目录及时摘除不可达节点。
 */
public final class RegistryLeaseReaper implements AutoCloseable {
    private final ServiceRegistry registry;
    private final Clock clock;
    private final Duration scanInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong expiredServices = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    public RegistryLeaseReaper(ServiceRegistry registry, Clock clock, Duration scanInterval) {
        this(registry, clock, scanInterval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "registry-lease-reaper");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public RegistryLeaseReaper(
            ServiceRegistry registry,
            Clock clock,
            Duration scanInterval,
            ScheduledExecutorService scheduler
    ) {
        this(registry, clock, scanInterval, scheduler, false);
    }

    private RegistryLeaseReaper(
            ServiceRegistry registry,
            Clock clock,
            Duration scanInterval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
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
        int expired = registry.expireLeases(clock.instant());
        expiredServices.addAndGet(expired);
        return expired;
    }

    public long expiredServices() {
        return expiredServices.get();
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
