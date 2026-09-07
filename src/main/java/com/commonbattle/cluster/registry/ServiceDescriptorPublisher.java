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
import java.util.function.Supplier;

/**
 * 动态服务描述发布器。
 * 服务内部负载、能力或摘流状态变化后，通过周期发布把最新 metadata 同步到注册中心。
 */
public final class ServiceDescriptorPublisher implements AutoCloseable, DrainableComponent {
    private final ServiceRegistry registry;
    private final Supplier<ServiceDescriptor> descriptorSupplier;
    private final Duration leaseTtl;
    private final Duration publishInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile ScheduledFuture<?> task;

    public ServiceDescriptorPublisher(
            ServiceRegistry registry,
            Supplier<ServiceDescriptor> descriptorSupplier,
            Duration leaseTtl,
            Duration publishInterval
    ) {
        this(registry, descriptorSupplier, leaseTtl, publishInterval, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "service-descriptor-publisher");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    public ServiceDescriptorPublisher(
            ServiceRegistry registry,
            Supplier<ServiceDescriptor> descriptorSupplier,
            Duration leaseTtl,
            Duration publishInterval,
            ScheduledExecutorService scheduler
    ) {
        this(registry, descriptorSupplier, leaseTtl, publishInterval, scheduler, false);
    }

    private ServiceDescriptorPublisher(
            ServiceRegistry registry,
            Supplier<ServiceDescriptor> descriptorSupplier,
            Duration leaseTtl,
            Duration publishInterval,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.descriptorSupplier = Objects.requireNonNull(descriptorSupplier, "descriptorSupplier");
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.publishInterval = positive(publishInterval, "publishInterval");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            publishOnce();
        } catch (RuntimeException e) {
            started.set(false);
            throw e;
        }
        task = scheduler.scheduleAtFixedRate(
                this::publishSafely,
                publishInterval.toMillis(),
                publishInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public void publishOnce() {
        attempts.incrementAndGet();
        try {
            registry.register(currentDescriptor(), leaseTtl);
            succeeded.incrementAndGet();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            throw e;
        }
    }

    public ServiceDescriptorPublisherStats stats() {
        return new ServiceDescriptorPublisherStats(
                attempts.get(),
                succeeded.get(),
                failed.get(),
                draining.get()
        );
    }

    @Override
    public DrainPhase phase() {
        return DrainPhase.EXTERNAL_ADVERTISEMENT;
    }

    @Override
    public void beginDrain() {
        draining.set(true);
        if (started.get()) {
            publishOnce();
        }
    }

    @Override
    public void resumeAccepting() {
        draining.set(false);
        if (started.get()) {
            publishOnce();
        }
    }

    @Override
    public boolean isDraining() {
        return draining.get();
    }

    private ServiceDescriptor currentDescriptor() {
        return ServiceMetadata.withDraining(descriptorSupplier.get(), draining.get());
    }

    private void publishSafely() {
        try {
            publishOnce();
        } catch (RuntimeException ignored) {
            // 发布失败时保留租约续约兜底，下一轮继续刷新动态 metadata。
        }
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
