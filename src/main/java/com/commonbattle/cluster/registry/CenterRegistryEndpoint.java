package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.RegistryEventType;
import com.commonbattle.cluster.RegistrySnapshot;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceRegistry;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 中心服上的网络注册中心端点。
 * 其他节点通过 RPC 注册和订阅，中心在服务变化时把 RegistryEvent 推回订阅者。
 */
public final class CenterRegistryEndpoint implements AutoCloseable, RegistrySubscriptionView {
    private final ServiceDescriptor center;
    private final ServiceRegistry registry;
    private final ClusterTransport transport;
    private final ClusterRpcGateway gateway;
    private final Clock clock;
    private final Duration subscriptionLeaseTtl;
    private final Map<ServiceKind, ConcurrentHashMap<ServiceId, SubscriptionLease>> subscribers =
            new EnumMap<>(ServiceKind.class);
    private final List<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong subscribeRequests = new AtomicLong();
    private final AtomicLong unsubscribeRequests = new AtomicLong();
    private final AtomicLong cleanedSubscribers = new AtomicLong();
    private final AtomicLong expiredSubscriptions = new AtomicLong();

    public CenterRegistryEndpoint(
            ServiceDescriptor center,
            ServiceRegistry registry,
            ClusterTransport transport,
            ClusterRpcGateway gateway
    ) {
        this(center, registry, transport, gateway, Clock.systemUTC(), Duration.ofSeconds(15));
    }

    public CenterRegistryEndpoint(
            ServiceDescriptor center,
            ServiceRegistry registry,
            ClusterTransport transport,
            ClusterRpcGateway gateway,
            Clock clock,
            Duration subscriptionLeaseTtl
    ) {
        this.center = Objects.requireNonNull(center, "center");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subscriptionLeaseTtl = positive(subscriptionLeaseTtl, "subscriptionLeaseTtl");
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new ConcurrentHashMap<>());
            subscriptions.add(registry.subscribe(kind, this::publish));
        }
        bindHandlers();
    }

    private void bindHandlers() {
        gateway.handle(RegistryOperations.REGISTER, (request, responder) -> {
            RegistryRegisterRequest payload = (RegistryRegisterRequest) request.payload();
            if (payload.leaseTtl().isZero()) {
                registry.register(payload.service());
            } else {
                registry.register(payload.service(), payload.leaseTtl());
            }
            responder.success(new RegistryAck("registered"));
        });
        gateway.handle(RegistryOperations.HEARTBEAT, (request, responder) -> {
            RegistryHeartbeatRequest payload = (RegistryHeartbeatRequest) request.payload();
            boolean renewed = registry.heartbeat(payload.serviceId(), payload.leaseTtl());
            responder.success(new RegistryAck(renewed ? "heartbeat.renewed" : "heartbeat.missing"));
        });
        gateway.handle(RegistryOperations.UNREGISTER, (request, responder) -> {
            RegistryUnregisterRequest payload = (RegistryUnregisterRequest) request.payload();
            registry.unregister(payload.serviceId());
            responder.success(new RegistryAck("unregistered"));
        });
        gateway.handle(RegistryOperations.LIST, (request, responder) -> {
            RegistryListRequest payload = (RegistryListRequest) request.payload();
            RegistrySnapshot snapshot = registry.snapshot(payload.kind());
            responder.success(new RegistryListResponse(snapshot.services(), snapshot.version()));
        });
        gateway.handle(RegistryOperations.SUBSCRIBE, (request, responder) -> {
            RegistrySubscribeRequest payload = (RegistrySubscribeRequest) request.payload();
            subscribeRequests.incrementAndGet();
            subscribers.get(payload.kind()).put(payload.subscriber(), new SubscriptionLease(expiresAt(payload.leaseTtl())));
            if (payload.sinceVersion() > 0) {
                registry.replay(payload.kind(), payload.sinceVersion()).forEach(event -> send(payload.subscriber(), event));
            } else {
                RegistrySnapshot snapshot = registry.snapshot(payload.kind());
                snapshot.services().forEach(service ->
                        send(payload.subscriber(), new RegistryEvent(RegistryEventType.REGISTERED, service)));
            }
            responder.success(new RegistryAck("subscribed"));
        });
        gateway.handle(RegistryOperations.UNSUBSCRIBE, (request, responder) -> {
            RegistryUnsubscribeRequest payload = (RegistryUnsubscribeRequest) request.payload();
            unsubscribeRequests.incrementAndGet();
            subscribers.get(payload.kind()).remove(payload.subscriber());
            responder.success(new RegistryAck("unsubscribed"));
        });
        gateway.handle(RegistryOperations.REPLAY, (request, responder) -> {
            RegistryReplayRequest payload = (RegistryReplayRequest) request.payload();
            List<RegistryEvent> events = registry.replay(payload.kind(), payload.sinceVersion());
            boolean compacted = payload.sinceVersion() < registry.minReplayVersion();
            responder.success(new RegistryReplayResponse(
                    compacted ? List.of() : events,
                    registry.version(),
                    compacted,
                    registry.minReplayVersion()
            ));
        });
    }

    private void publish(RegistryEvent event) {
        subscribers.get(event.service().id().kind()).keySet().forEach(subscriber -> send(subscriber, event));
        if (event.type() == RegistryEventType.UNREGISTERED) {
            cleanupSubscriber(event.service().id());
        }
    }

    private void cleanupSubscriber(ServiceId subscriber) {
        long removed = 0;
        for (ConcurrentHashMap<ServiceId, SubscriptionLease> byKind : subscribers.values()) {
            if (byKind.remove(subscriber) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            cleanedSubscribers.addAndGet(removed);
        }
    }

    public int expireSubscriptions(Instant now) {
        Objects.requireNonNull(now, "now");
        int expired = 0;
        for (ConcurrentHashMap<ServiceId, SubscriptionLease> byKind : subscribers.values()) {
            for (Map.Entry<ServiceId, SubscriptionLease> entry : byKind.entrySet()) {
                if (!entry.getValue().expiresAt().isAfter(now) && byKind.remove(entry.getKey(), entry.getValue())) {
                    expired++;
                }
            }
        }
        if (expired > 0) {
            expiredSubscriptions.addAndGet(expired);
        }
        return expired;
    }

    private void send(ServiceId subscriber, RegistryEvent event) {
        ClusterEnvelope envelope = new ClusterEnvelope(
                0,
                center.id(),
                subscriber,
                RegistryOperations.EVENT,
                event
        );
        transport.send(subscriber, envelope);
    }

    @Override
    public RegistrySubscriptionStats stats() {
        int subscribedKinds = 0;
        int references = 0;
        Set<ServiceId> uniqueSubscribers = new HashSet<>();
        for (ConcurrentHashMap<ServiceId, SubscriptionLease> byKind : subscribers.values()) {
            if (!byKind.isEmpty()) {
                subscribedKinds++;
            }
            references += byKind.size();
            uniqueSubscribers.addAll(byKind.keySet());
        }
        return new RegistrySubscriptionStats(
                subscribedKinds,
                uniqueSubscribers.size(),
                references,
                subscribeRequests.get(),
                unsubscribeRequests.get(),
                cleanedSubscribers.get(),
                expiredSubscriptions.get()
        );
    }

    @Override
    public void close() {
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close center registry subscription", e);
            }
        }
        subscriptions.clear();
    }

    private Instant expiresAt(Duration requestedTtl) {
        Duration ttl = requestedTtl.isZero() ? subscriptionLeaseTtl : requestedTtl;
        return clock.instant().plus(ttl);
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record SubscriptionLease(Instant expiresAt) {
        private SubscriptionLease {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
