package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.RegistryEvent;
import com.commonbattle.cluster.RegistryEventType;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceRegistry;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 中心服上的网络注册中心端点。
 * 其他节点通过 RPC 注册和订阅，中心在服务变化时把 RegistryEvent 推回订阅者。
 */
public final class CenterRegistryEndpoint implements AutoCloseable {
    private final ServiceDescriptor center;
    private final ServiceRegistry registry;
    private final ClusterTransport transport;
    private final ClusterRpcGateway gateway;
    private final Map<ServiceKind, CopyOnWriteArrayList<ServiceId>> subscribers = new EnumMap<>(ServiceKind.class);
    private final List<AutoCloseable> subscriptions = new CopyOnWriteArrayList<>();

    public CenterRegistryEndpoint(
            ServiceDescriptor center,
            ServiceRegistry registry,
            ClusterTransport transport,
            ClusterRpcGateway gateway
    ) {
        this.center = Objects.requireNonNull(center, "center");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        for (ServiceKind kind : ServiceKind.values()) {
            subscribers.put(kind, new CopyOnWriteArrayList<>());
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
            responder.success(new RegistryListResponse(registry.list(payload.kind())));
        });
        gateway.handle(RegistryOperations.SUBSCRIBE, (request, responder) -> {
            RegistrySubscribeRequest payload = (RegistrySubscribeRequest) request.payload();
            subscribers.get(payload.kind()).addIfAbsent(payload.subscriber());
            registry.list(payload.kind()).forEach(service ->
                    send(payload.subscriber(), new RegistryEvent(RegistryEventType.REGISTERED, service)));
            responder.success(new RegistryAck("subscribed"));
        });
    }

    private void publish(RegistryEvent event) {
        subscribers.get(event.service().id().kind()).forEach(subscriber -> send(subscriber, event));
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
}
