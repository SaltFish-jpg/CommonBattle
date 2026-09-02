package com.commonbattle.cluster;

import com.commonbattle.cluster.registry.RegistryLeaseRenewer;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 跨服节点启动流程封装。
 * 节点先注册自身服务，再订阅依赖的服务类型；关闭时取消注册并释放订阅。
 */
public final class ClusterNode implements AutoCloseable {
    private final ServiceRegistry registry;
    private final ServiceDescriptor local;
    private final ClusterDirectory directory;
    private RegistryLeaseRenewer leaseRenewer;
    private boolean started;

    public ClusterNode(ServiceRegistry registry, ServiceDescriptor local) {
        this(registry, local, new ClusterDirectory(registry));
    }

    public ClusterNode(ServiceRegistry registry, ServiceDescriptor local, ClusterDirectory directory) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.local = Objects.requireNonNull(local, "local");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public ServiceDescriptor local() {
        return local;
    }

    public ClusterDirectory directory() {
        return directory;
    }

    public Optional<RegistryLeaseRenewer> leaseRenewer() {
        return Optional.ofNullable(leaseRenewer);
    }

    public void start(Collection<ServiceKind> watches) {
        if (started) {
            return;
        }
        Objects.requireNonNull(watches, "watches").forEach(directory::watch);
        registry.register(local);
        started = true;
    }

    public void start(Collection<ServiceKind> watches, Duration leaseTtl, Duration heartbeatInterval) {
        if (started) {
            return;
        }
        Objects.requireNonNull(watches, "watches").forEach(directory::watch);
        leaseRenewer = new RegistryLeaseRenewer(registry, local, leaseTtl, heartbeatInterval);
        leaseRenewer.start();
        started = true;
    }

    public void start(
            Collection<ServiceKind> watches,
            Duration leaseTtl,
            Duration heartbeatInterval,
            ScheduledExecutorService scheduler
    ) {
        if (started) {
            return;
        }
        Objects.requireNonNull(watches, "watches").forEach(directory::watch);
        leaseRenewer = new RegistryLeaseRenewer(registry, local, leaseTtl, heartbeatInterval, scheduler);
        leaseRenewer.start();
        started = true;
    }

    @Override
    public void close() {
        if (started) {
            if (leaseRenewer != null) {
                leaseRenewer.close();
                leaseRenewer = null;
            } else {
                registry.unregister(local.id());
            }
            started = false;
        }
        directory.close();
    }
}
