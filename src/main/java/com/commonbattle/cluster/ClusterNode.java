package com.commonbattle.cluster;

import java.util.Collection;
import java.util.Objects;

/**
 * 跨服节点启动流程封装。
 * 节点先注册自身服务，再订阅依赖的服务类型；关闭时取消注册并释放订阅。
 */
public final class ClusterNode implements AutoCloseable {
    private final ServiceRegistry registry;
    private final ServiceDescriptor local;
    private final ClusterDirectory directory;
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

    public void start(Collection<ServiceKind> watches) {
        if (started) {
            return;
        }
        Objects.requireNonNull(watches, "watches").forEach(directory::watch);
        registry.register(local);
        started = true;
    }

    @Override
    public void close() {
        if (started) {
            registry.unregister(local.id());
            started = false;
        }
        directory.close();
    }
}
