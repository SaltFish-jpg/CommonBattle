package com.commonbattle.cluster;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单进程内存注册中心。
 * 适合单元测试、沙盘推演和把注册订阅流程先接入业务层。
 */
public final class InMemoryServiceRegistry implements ServiceRegistry {
    private final Map<ServiceId, ServiceDescriptor> services = new ConcurrentHashMap<>();
    private final Map<ServiceKind, CopyOnWriteArrayList<RegistrySubscriber>> subscribers =
            new EnumMap<>(ServiceKind.class);

    public InMemoryServiceRegistry() {
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public void register(ServiceDescriptor service) {
        Objects.requireNonNull(service, "service");
        services.put(service.id(), service);
        publish(new RegistryEvent(RegistryEventType.REGISTERED, service));
    }

    @Override
    public void unregister(ServiceId serviceId) {
        ServiceDescriptor removed = services.remove(Objects.requireNonNull(serviceId, "serviceId"));
        if (removed != null) {
            publish(new RegistryEvent(RegistryEventType.UNREGISTERED, removed));
        }
    }

    @Override
    public List<ServiceDescriptor> list(ServiceKind kind) {
        Objects.requireNonNull(kind, "kind");
        return services.values().stream()
                .filter(service -> service.id().kind() == kind)
                .toList();
    }

    @Override
    public AutoCloseable subscribe(ServiceKind kind, RegistrySubscriber subscriber) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.get(kind).add(subscriber);
        list(kind).forEach(service -> subscriber.onEvent(new RegistryEvent(RegistryEventType.REGISTERED, service)));
        return () -> subscribers.get(kind).remove(subscriber);
    }

    private void publish(RegistryEvent event) {
        List<RegistrySubscriber> snapshot = new ArrayList<>(subscribers.get(event.service().id().kind()));
        snapshot.forEach(subscriber -> subscriber.onEvent(event));
    }
}
