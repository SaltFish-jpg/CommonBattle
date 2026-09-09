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
import java.util.concurrent.atomic.AtomicLong;
import com.commonbattle.cluster.registry.RegistryHistoryStats;
import com.commonbattle.cluster.registry.RegistryHistoryView;

/**
 * 单进程内存注册中心。
 * 适合单元测试、沙盘推演和把注册订阅流程先接入业务层。
 */
public final class InMemoryServiceRegistry implements ServiceRegistry, RegistryHistoryView {
    public static final int DEFAULT_HISTORY_LIMIT = 10_000;

    private final Clock clock;
    private final int historyLimit;
    private final Map<ServiceId, ServiceDescriptor> services = new ConcurrentHashMap<>();
    private final Map<ServiceId, Instant> leases = new ConcurrentHashMap<>();
    private final List<RegistryEvent> history = new ArrayList<>();
    private final Map<ServiceKind, CopyOnWriteArrayList<RegistrySubscriber>> subscribers =
            new EnumMap<>(ServiceKind.class);
    private final AtomicLong compactedReplayRequests = new AtomicLong();
    private long version;

    public InMemoryServiceRegistry() {
        this(Clock.systemUTC());
    }

    public InMemoryServiceRegistry(Clock clock) {
        this(clock, DEFAULT_HISTORY_LIMIT);
    }

    public InMemoryServiceRegistry(Clock clock, int historyLimit) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (historyLimit < 0) {
            throw new IllegalArgumentException("historyLimit must not be negative");
        }
        this.historyLimit = historyLimit;
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public void register(ServiceDescriptor service) {
        RegistryEvent event;
        synchronized (this) {
            Objects.requireNonNull(service, "service");
            services.put(service.id(), service);
            leases.remove(service.id());
            event = append(RegistryEventType.REGISTERED, service);
        }
        publish(event);
    }

    @Override
    public void register(ServiceDescriptor service, Duration leaseTtl) {
        RegistryEvent event;
        synchronized (this) {
            Objects.requireNonNull(service, "service");
            validateLeaseTtl(leaseTtl);
            services.put(service.id(), service);
            leases.put(service.id(), clock.instant().plus(leaseTtl));
            event = append(RegistryEventType.REGISTERED, service);
        }
        publish(event);
    }

    @Override
    public void unregister(ServiceId serviceId) {
        RegistryEvent event = null;
        synchronized (this) {
            ServiceId id = Objects.requireNonNull(serviceId, "serviceId");
            leases.remove(id);
            ServiceDescriptor removed = services.remove(id);
            if (removed != null) {
                event = append(RegistryEventType.UNREGISTERED, removed);
            }
        }
        if (event != null) {
            publish(event);
        }
    }

    @Override
    public synchronized boolean heartbeat(ServiceId serviceId, Duration leaseTtl) {
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
        List<RegistryEvent> expiredEvents = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<ServiceId, Instant> lease : List.copyOf(leases.entrySet())) {
                if (!lease.getValue().isAfter(now) && leases.remove(lease.getKey(), lease.getValue())) {
                    ServiceDescriptor removed = services.remove(lease.getKey());
                    if (removed != null) {
                        expiredEvents.add(append(RegistryEventType.UNREGISTERED, removed));
                    }
                }
            }
        }
        expiredEvents.forEach(this::publish);
        return expiredEvents.size();
    }

    @Override
    public synchronized List<ServiceDescriptor> list(ServiceKind kind) {
        Objects.requireNonNull(kind, "kind");
        return services.values().stream()
                .filter(service -> service.id().kind() == kind)
                .toList();
    }

    @Override
    public synchronized RegistrySnapshot snapshot(ServiceKind kind) {
        Objects.requireNonNull(kind, "kind");
        return new RegistrySnapshot(
                kind,
                services.values().stream()
                        .filter(service -> service.id().kind() == kind)
                        .toList(),
                version
        );
    }

    @Override
    public synchronized List<RegistryEvent> replay(ServiceKind kind, long sinceVersion) {
        Objects.requireNonNull(kind, "kind");
        if (sinceVersion < 0) {
            throw new IllegalArgumentException("sinceVersion must not be negative");
        }
        if (sinceVersion < minReplayVersion()) {
            compactedReplayRequests.incrementAndGet();
            return List.of();
        }
        return history.stream()
                .filter(event -> event.version() > sinceVersion)
                .filter(event -> event.service().id().kind() == kind)
                .toList();
    }

    @Override
    public synchronized long version() {
        return version;
    }

    @Override
    public synchronized long minReplayVersion() {
        if (history.isEmpty()) {
            return version;
        }
        return Math.max(0, history.getFirst().version() - 1);
    }

    @Override
    public synchronized RegistryHistoryStats stats() {
        return new RegistryHistoryStats(
                version,
                minReplayVersion(),
                history.size(),
                historyLimit,
                compactedReplayRequests.get()
        );
    }

    @Override
    public AutoCloseable subscribe(ServiceKind kind, RegistrySubscriber subscriber) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.get(kind).add(subscriber);
        RegistrySnapshot snapshot = snapshot(kind);
        snapshot.services().forEach(service ->
                subscriber.onEvent(new RegistryEvent(RegistryEventType.REGISTERED, service)));
        return () -> subscribers.get(kind).remove(subscriber);
    }

    private RegistryEvent append(RegistryEventType type, ServiceDescriptor service) {
        RegistryEvent event = new RegistryEvent(type, service, ++version);
        if (historyLimit > 0) {
            history.add(event);
            while (history.size() > historyLimit) {
                history.removeFirst();
            }
        }
        return event;
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
