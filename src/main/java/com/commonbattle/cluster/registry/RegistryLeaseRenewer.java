package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.ServiceRegistry;
import com.commonbattle.runtime.DrainableComponent;
import com.commonbattle.runtime.DrainPhase;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务注册租约续约器。
 * 节点启动时先带 TTL 注册自身，之后周期心跳；如果中心返回租约不存在，则立即重新注册。
 */
public final class RegistryLeaseRenewer implements AutoCloseable, DrainableComponent {
    private final ServiceRegistry registry;
    private final ServiceDescriptor local;
    private final Duration leaseTtl;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong successfulHeartbeats = new AtomicLong();
    private final AtomicLong reRegistrations = new AtomicLong();
    private final AtomicLong failedRenewals = new AtomicLong();
    private volatile ServiceDescriptor registered;
    private volatile ScheduledFuture<?> task;

    public RegistryLeaseRenewer(
            ServiceRegistry registry,
            ServiceDescriptor local,
            Duration leaseTtl,
            Duration heartbeatInterval
    ) {
        this(registry, local, leaseTtl, heartbeatInterval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "registry-lease-renewer-" + local.id().wireName());
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public RegistryLeaseRenewer(
            ServiceRegistry registry,
            ServiceDescriptor local,
            Duration leaseTtl,
            Duration heartbeatInterval,
            ScheduledExecutorService scheduler
    ) {
        this(registry, local, leaseTtl, heartbeatInterval, scheduler, false);
    }

    private RegistryLeaseRenewer(
            ServiceRegistry registry,
            ServiceDescriptor local,
            Duration leaseTtl,
            Duration heartbeatInterval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.local = Objects.requireNonNull(local, "local");
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.registered = local;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        registry.register(registered, leaseTtl);
        task = scheduler.scheduleAtFixedRate(
                this::renewSafely,
                heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public void renewOnce() {
        try {
            if (!registry.heartbeat(local.id(), leaseTtl)) {
                registry.register(registered, leaseTtl);
                reRegistrations.incrementAndGet();
                return;
            }
            successfulHeartbeats.incrementAndGet();
        } catch (RuntimeException e) {
            failedRenewals.incrementAndGet();
            throw e;
        }
    }

    public RegistryLeaseRenewalStats stats() {
        return new RegistryLeaseRenewalStats(
                successfulHeartbeats.get(),
                reRegistrations.get(),
                failedRenewals.get()
        );
    }

    @Override
    public DrainPhase phase() {
        return DrainPhase.EXTERNAL_ADVERTISEMENT;
    }

    @Override
    public void beginDrain() {
        registered = ServiceMetadata.withDraining(local, true);
        if (started.get()) {
            registerCurrent();
        }
    }

    @Override
    public void resumeAccepting() {
        registered = local;
        if (started.get()) {
            registerCurrent();
        }
    }

    @Override
    public boolean isDraining() {
        return registered.draining();
    }

    private void renewSafely() {
        try {
            renewOnce();
        } catch (RuntimeException e) {
        }
    }

    private void registerCurrent() {
        try {
            registry.register(registered, leaseTtl);
        } catch (RuntimeException e) {
            failedRenewals.incrementAndGet();
            throw e;
        }
    }

    @Override
    public void close() {
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        if (started.compareAndSet(true, false)) {
            registry.unregister(local.id());
        }
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
