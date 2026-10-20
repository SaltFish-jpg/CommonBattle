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
        registry.register(local);
        try {
            watch(watches);
            started = true;
        } catch (RuntimeException error) {
            rollbackRegistration(error);
            throw error;
        }
    }

    public void start(Collection<ServiceKind> watches, Duration leaseTtl, Duration heartbeatInterval) {
        if (started) {
            return;
        }
        RegistryLeaseRenewer renewer = new RegistryLeaseRenewer(registry, local, leaseTtl, heartbeatInterval);
        startWithLease(watches, renewer);
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
        RegistryLeaseRenewer renewer = new RegistryLeaseRenewer(
                registry, local, leaseTtl, heartbeatInterval, scheduler);
        startWithLease(watches, renewer);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        if (started) {
            try {
                if (leaseRenewer != null) {
                    leaseRenewer.close();
                } else {
                    registry.unregister(local.id());
                }
            } catch (RuntimeException error) {
                failure = error;
            } finally {
                leaseRenewer = null;
                started = false;
            }
        }
        try {
            directory.close();
        } catch (RuntimeException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void startWithLease(Collection<ServiceKind> watches, RegistryLeaseRenewer renewer) {
        try {
            renewer.start();
            leaseRenewer = renewer;
            watch(watches);
            started = true;
        } catch (RuntimeException error) {
            try {
                renewer.close();
            } catch (RuntimeException closeError) {
                error.addSuppressed(closeError);
            }
            leaseRenewer = null;
            closeDirectoryAfterFailedStart(error);
            throw error;
        }
    }

    private void watch(Collection<ServiceKind> watches) {
        Objects.requireNonNull(watches, "watches")
                .forEach(kind -> directory.watch(kind, registry));
    }

    private void rollbackRegistration(RuntimeException error) {
        try {
            registry.unregister(local.id());
        } catch (RuntimeException unregisterError) {
            error.addSuppressed(unregisterError);
        }
        closeDirectoryAfterFailedStart(error);
    }

    private void closeDirectoryAfterFailedStart(RuntimeException error) {
        try {
            directory.close();
        } catch (RuntimeException closeError) {
            error.addSuppressed(closeError);
        }
    }
}
