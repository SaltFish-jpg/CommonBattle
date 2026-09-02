package com.commonbattle.cluster;

import java.util.ArrayList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Clock clock;
    private final Map<ServiceId, ServiceDescriptor> services = new ConcurrentHashMap<>();
    private final Map<ServiceId, Instant> leases = new ConcurrentHashMap<>();
    private final Map<ServiceKind, CopyOnWriteArrayList<RegistrySubscriber>> subscribers =
            new EnumMap<>(ServiceKind.class);

    public InMemoryServiceRegistry() {
        this(Clock.systemUTC());
    }

    public InMemoryServiceRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public void register(ServiceDescriptor service) {
        Objects.requireNonNull(service, "service");
        services.put(service.id(), service);
        leases.remove(service.id());
        publish(new RegistryEvent(RegistryEventType.REGISTERED, service));
    }

    @Override
    public void register(ServiceDescriptor service, Duration leaseTtl) {
        Objects.requireNonNull(service, "service");
        validateLeaseTtl(leaseTtl);
        services.put(service.id(), service);
        leases.put(service.id(), clock.instant().plus(leaseTtl));
        publish(new RegistryEvent(RegistryEventType.REGISTERED, service));
    }

    @Override
    public void unregister(ServiceId serviceId) {
        ServiceId id = Objects.requireNonNull(serviceId, "serviceId");
        leases.remove(id);
        ServiceDescriptor removed = services.remove(id);
        if (removed != null) {
            publish(new RegistryEvent(RegistryEventType.UNREGISTERED, removed));
        }
    }

    @Override
    public boolean heartbeat(ServiceId serviceId, Duration leaseTtl) {
        ServiceId id = Objects.requireNonNull(serviceId, "serviceId");
        validateLeaseTtl(leaseTtl);
        if (!services.containsKey(id)) {
            return false;
        }
        leases.put(id, clock.instant().plus(leaseTtl));
        return true;
    }

    @Override
    public int expireLeases(Instant now) {
        Objects.requireNonNull(now, "now");
        int expired = 0;
        for (Map.Entry<ServiceId, Instant> lease : List.copyOf(leases.entrySet())) {
            if (!lease.getValue().isAfter(now) && leases.remove(lease.getKey(), lease.getValue())) {
                ServiceDescriptor removed = services.remove(lease.getKey());
                if (removed != null) {
                    expired++;
                    publish(new RegistryEvent(RegistryEventType.UNREGISTERED, removed));
                }
            }
        }
        return expired;
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

    private void validateLeaseTtl(Duration leaseTtl) {
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isZero() || leaseTtl.isNegative()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
    }
}
