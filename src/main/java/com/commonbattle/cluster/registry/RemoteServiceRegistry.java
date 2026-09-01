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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过中心服 RPC 访问的远程注册中心。
 * 本地维护订阅快照，业务查询不需要阻塞 Actor；注册、取消注册和首次订阅是启动流程中的同步操作。
 */
public final class RemoteServiceRegistry implements ServiceRegistry {
    private final ServiceId local;
    private final ClusterRpcGateway gateway;
    private final ClusterDirectory mirror;
    private final java.util.Map<ServiceId, ServiceDescriptor> cache = new ConcurrentHashMap<>();
    private final java.util.Map<ServiceKind, CopyOnWriteArrayList<RegistrySubscriber>> subscribers =
            new EnumMap<>(ServiceKind.class);

    public RemoteServiceRegistry(ServiceId local, ClusterRpcGateway gateway) {
        this(local, gateway, null);
    }

    public RemoteServiceRegistry(ServiceId local, ClusterRpcGateway gateway, ClusterDirectory mirror) {
        this.local = Objects.requireNonNull(local, "local");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.mirror = mirror;
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new CopyOnWriteArrayList<>());
        }
        this.gateway.handle(RegistryOperations.EVENT, (request, responder) -> {
            RegistryEvent event = (RegistryEvent) request.payload();
            apply(event);
            responder.success(new RegistryAck("event.received"));
        });
    }

    @Override
    public void register(ServiceDescriptor service) {
        await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.REGISTER,
                new RegistryRegisterRequest(service),
                RegistryAck.class
        ));
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
        RegistryListResponse snapshot = await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.LIST,
                new RegistryListRequest(kind),
                RegistryListResponse.class
        ));
        snapshot.services().forEach(service -> apply(new RegistryEvent(RegistryEventType.REGISTERED, service)));
        await(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                RegistryOperations.SUBSCRIBE,
                new RegistrySubscribeRequest(local, kind),
                RegistryAck.class
        ));
        return () -> subscribers.get(kind).remove(subscriber);
    }

    private void apply(RegistryEvent event) {
        if (event.type() == RegistryEventType.REGISTERED) {
            cache.put(event.service().id(), event.service());
        } else {
            cache.remove(event.service().id());
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
}
