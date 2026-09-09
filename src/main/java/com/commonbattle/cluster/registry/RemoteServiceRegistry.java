package com.commonbattle.cluster.registry;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.RegistryEventType;
import com.commonbattle.cluster.RegistrySubscriber;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceRegistry;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过中心服 RPC 访问的远程注册中心。
 * 本地维护订阅快照，业务查询不需要阻塞 Actor；注册、取消注册和首次订阅是启动流程中的同步操作。
 */
public final class RemoteServiceRegistry implements ServiceRegistry {
    private final ServiceId local;
    private final ClusterRpcGateway gateway;
    private final ClusterDirectory mirror;
    private final Duration subscriptionLeaseTtl;
    private final java.util.Map<ServiceId, ServiceDescriptor> cache = new ConcurrentHashMap<>();
    private final java.util.Map<ServiceKind, Long> versions = new ConcurrentHashMap<>();
    private final java.util.Map<ServiceKind, AtomicInteger> remoteReferences = new EnumMap<>(ServiceKind.class);
    private final java.util.Map<ServiceKind, CopyOnWriteArrayList<RegistrySubscriber>> subscribers =
            new EnumMap<>(ServiceKind.class);

    public RemoteServiceRegistry(ServiceId local, ClusterRpcGateway gateway) {
        this(local, gateway, null);
    }

    public RemoteServiceRegistry(ServiceId local, ClusterRpcGateway gateway, ClusterDirectory mirror) {
        this(local, gateway, mirror, Duration.ofSeconds(15));
    }

    public RemoteServiceRegistry(
            ServiceId local,
            ClusterRpcGateway gateway,
            ClusterDirectory mirror,
            Duration subscriptionLeaseTtl
    ) {
        this.local = Objects.requireNonNull(local, "local");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.mirror = mirror;
        this.subscriptionLeaseTtl = positive(subscriptionLeaseTtl, "subscriptionLeaseTtl");
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new CopyOnWriteArrayList<>());
            versions.put(kind, 0L);
            remoteReferences.put(kind, new AtomicInteger());
        }
        this.gateway.handle(RegistryOperations.EVENT, (request, responder) -> {
            RegistryEvent event = (RegistryEvent) request.payload();
            apply(event);
            responder.success(new RegistryAck("event.received"));
        });
    }

    @Override
    public void register(ServiceDescriptor service) {
        register(service, Duration.ZERO);
    }

    @Override
    public void register(ServiceDescriptor service, Duration leaseTtl) {
        await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.REGISTER,
                new RegistryRegisterRequest(service, leaseTtl),
                RegistryAck.class
        ));
    }

    @Override
    public boolean heartbeat(ServiceId serviceId, Duration leaseTtl) {
        RegistryAck ack = await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.HEARTBEAT,
                new RegistryHeartbeatRequest(serviceId, leaseTtl),
                RegistryAck.class
        ));
        return "heartbeat.renewed".equals(ack.message());
    }

    @Override
    public void unregister(ServiceId serviceId) {
        await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.UNREGISTER,
                new RegistryUnregisterRequest(serviceId),
                RegistryAck.class
        ));
    }

    @Override
    public List<ServiceDescriptor> list(ServiceKind kind) {
        return cache.values().stream()
                .filter(service -> service.id().kind() == kind)
                .toList();
    }

    @Override
    public AutoCloseable subscribe(ServiceKind kind, RegistrySubscriber subscriber) {
        subscribers.get(kind).add(Objects.requireNonNull(subscriber, "subscriber"));
        int references = remoteReferences.get(kind).incrementAndGet();
        try {
            RegistryListResponse snapshot = refresh(kind);
            if (references == 1) {
                await(new RpcRequest<>(
                        ServiceKind.CENTER.name(),
                        RegistryOperations.SUBSCRIBE,
                        new RegistrySubscribeRequest(local, kind, snapshot.version(), subscriptionLeaseTtl),
                        RegistryAck.class
                ));
            }
        } catch (RuntimeException e) {
            subscribers.get(kind).remove(subscriber);
            remoteReferences.get(kind).decrementAndGet();
            throw e;
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            subscribers.get(kind).remove(subscriber);
            int remaining = remoteReferences.get(kind).decrementAndGet();
            if (remaining == 0) {
                await(new RpcRequest<>(
                        ServiceKind.CENTER.name(),
                        RegistryOperations.UNSUBSCRIBE,
                        new RegistryUnsubscribeRequest(local, kind),
                        RegistryAck.class
                ));
            } else if (remaining < 0) {
                remoteReferences.get(kind).set(0);
                throw new IllegalStateException("Registry subscription reference underflow: " + kind);
            }
        };
    }

    public int recoverSubscriptions() {
        int recoveredKinds = 0;
        for (ServiceKind kind : ServiceKind.values()) {
            if (subscribers.get(kind).isEmpty()) {
                continue;
            }
            RegistryReplayResponse replay = await(new RpcRequest<>(
                    ServiceKind.CENTER.name(),
                    RegistryOperations.REPLAY,
                    new RegistryReplayRequest(local, kind, versions.get(kind)),
                    RegistryReplayResponse.class
            ));
            if (replay.compacted()) {
                refresh(kind);
            } else {
                replay.events().forEach(this::apply);
                versions.compute(kind, (ignored, current) -> Math.max(current == null ? 0 : current, replay.currentVersion()));
            }
            await(new RpcRequest<>(
                        ServiceKind.CENTER.name(),
                        RegistryOperations.SUBSCRIBE,
                        new RegistrySubscribeRequest(local, kind, versions.get(kind), subscriptionLeaseTtl),
                        RegistryAck.class
                ));
            recoveredKinds++;
        }
        return recoveredKinds;
    }

    private RegistryListResponse refresh(ServiceKind kind) {
        RegistryListResponse snapshot = await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.LIST,
                new RegistryListRequest(kind),
                RegistryListResponse.class
        ));
        replace(kind, snapshot.services(), snapshot.version());
        return snapshot;
    }

    private void replace(ServiceKind kind, List<ServiceDescriptor> services, long version) {
        List<ServiceDescriptor> existing = cache.values().stream()
                .filter(service -> service.id().kind() == kind)
                .toList();
        java.util.Map<ServiceId, ServiceDescriptor> next = new java.util.HashMap<>();
        services.forEach(service -> next.put(service.id(), service));
        List<RegistryEvent> syntheticEvents = new ArrayList<>();
        for (ServiceDescriptor current : existing) {
            if (!next.containsKey(current.id())) {
                cache.remove(current.id());
                syntheticEvents.add(new RegistryEvent(RegistryEventType.UNREGISTERED, current));
            }
        }
        for (ServiceDescriptor service : services) {
            ServiceDescriptor previous = cache.put(service.id(), service);
            if (!service.equals(previous)) {
                syntheticEvents.add(new RegistryEvent(RegistryEventType.REGISTERED, service));
            }
        }
        versions.put(kind, version);
        if (mirror != null) {
            mirror.replace(kind, services, version);
        }
        syntheticEvents.forEach(event ->
                subscribers.get(event.service().id().kind()).forEach(subscriber -> subscriber.onEvent(event)));
    }

    private void apply(RegistryEvent event) {
        if (event.version() > 0 && event.version() <= versions.get(event.service().id().kind())) {
            return;
        }
        if (event.type() == RegistryEventType.REGISTERED) {
            cache.put(event.service().id(), event.service());
        } else {
            cache.remove(event.service().id());
        }
        if (event.version() > 0) {
            versions.put(event.service().id().kind(), event.version());
        }
        if (mirror != null) {
            mirror.accept(event);
        }
        subscribers.get(event.service().id().kind()).forEach(subscriber -> subscriber.onEvent(event));
    }

    private <T> T await(RpcRequest<T> request) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        gateway.call(request, new RpcCallback<>() {
            @Override
            public void success(T response) {
                value.set(response);
                done.countDown();
            }

            @Override
            public void failure(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        try {
            if (!done.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Registry RPC timeout: " + request.operation());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting registry RPC", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Registry RPC failed: " + request.operation(), failure.get());
        }
        return value.get();
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
