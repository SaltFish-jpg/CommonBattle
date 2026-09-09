package com.commonbattle.cluster;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订阅注册中心后的本地服务目录。
 * 节点启动时订阅自己关心的服务类型，RPC 路由只读取本地快照，不阻塞业务 Actor。
 */
public final class ClusterDirectory implements AutoCloseable {
    private final ServiceRegistry registry;
    private final Map<ServiceKind, CopyOnWriteArrayList<ServiceDescriptor>> services =
            new EnumMap<>(ServiceKind.class);
    private final Map<ServiceKind, Long> versions = new EnumMap<>(ServiceKind.class);
    private final List<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();

    public ClusterDirectory(ServiceRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (ServiceKind kind : ServiceKind.values()) {
            services.put(kind, new CopyOnWriteArrayList<>());
            versions.put(kind, 0L);
        }
    }

    public void watch(ServiceKind kind) {
        subscriptions.add(registry.subscribe(kind, this::onEvent));
    }

    public List<ServiceDescriptor> list(ServiceKind kind) {
        return List.copyOf(services.get(kind));
    }

    public List<ServiceDescriptor> routable(ServiceKind kind) {
        return services.get(kind).stream()
                .filter(service -> !service.draining())
                .toList();
    }

    public Optional<ServiceDescriptor> find(ServiceId serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        return services.get(serviceId.kind()).stream()
                .filter(service -> service.id().equals(serviceId))
                .findFirst();
    }

    public Optional<ServiceDescriptor> routable(ServiceId serviceId) {
        return find(serviceId).filter(service -> !service.draining());
    }

    public void seed(ServiceDescriptor service) {
        accept(new RegistryEvent(RegistryEventType.REGISTERED, Objects.requireNonNull(service, "service")));
    }

    public void replace(ServiceKind kind, List<ServiceDescriptor> snapshot, long version) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(snapshot, "snapshot");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        CopyOnWriteArrayList<ServiceDescriptor> byKind = services.get(kind);
        byKind.clear();
        byKind.addAll(snapshot);
        versions.put(kind, version);
    }

    public long version(ServiceKind kind) {
        Objects.requireNonNull(kind, "kind");
        return versions.get(kind);
    }

    public void accept(RegistryEvent event) {
        onEvent(event);
    }

    public ServiceDescriptor first(ServiceKind kind) {
        return routable(kind).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No service registered for " + kind));
    }

    private void onEvent(RegistryEvent event) {
        CopyOnWriteArrayList<ServiceDescriptor> byKind = services.get(event.service().id().kind());
        if (isStale(event)) {
            return;
        }
        if (event.type() == RegistryEventType.REGISTERED) {
            byKind.removeIf(service -> service.id().equals(event.service().id()));
            byKind.add(event.service());
        } else {
            byKind.removeIf(service -> service.id().equals(event.service().id()));
        }
        recordVersion(event);
    }

    private boolean isStale(RegistryEvent event) {
        if (event.version() == 0) {
            return false;
        }
        ServiceKind kind = event.service().id().kind();
        return event.version() <= versions.get(kind);
    }

    private void recordVersion(RegistryEvent event) {
        if (event.version() > 0) {
            versions.put(event.service().id().kind(), event.version());
        }
    }

    @Override
    public void close() {
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close registry subscription", e);
            }
        }
        subscriptions.clear();
    }
}
