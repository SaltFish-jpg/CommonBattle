package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcEndpointHandler;
import com.commonbattle.cluster.rpc.RpcResponder;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨服事件中心。
 * 负责维护 topic -> subscriber 的订阅表，发布事件时按 ServiceId 推送给订阅服务。
 */
public final class ClusterEventCenter {
    private final ServiceDescriptor local;
    private final ClusterTransport transport;
    private final Map<String, Set<ServiceId>> subscribers = new ConcurrentHashMap<>();

    public ClusterEventCenter(ServiceDescriptor local, ClusterTransport transport, ClusterRpcGateway gateway) {
        this.local = Objects.requireNonNull(local, "local");
        this.transport = Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(gateway, "gateway");
        gateway.handle(ClusterEventOperations.SUBSCRIBE, this::subscribe);
        gateway.handle(ClusterEventOperations.UNSUBSCRIBE, this::unsubscribe);
        gateway.handle(ClusterEventOperations.PUBLISH, this::publish);
    }

    public Set<ServiceId> subscribers(String topic) {
        return Set.copyOf(subscribers.getOrDefault(topic, Set.of()));
    }

    private void subscribe(ClusterEnvelope request, RpcResponder responder) {
        EventSubscribeRequest payload = (EventSubscribeRequest) request.payload();
        subscribers.computeIfAbsent(payload.topic(), ignored -> ConcurrentHashMap.newKeySet())
                .add(payload.subscriber());
        responder.success("subscribed");
    }

    private void unsubscribe(ClusterEnvelope request, RpcResponder responder) {
        EventUnsubscribeRequest payload = (EventUnsubscribeRequest) request.payload();
        subscribers.getOrDefault(payload.topic(), Set.of()).remove(payload.subscriber());
        responder.success("unsubscribed");
    }

    private void publish(ClusterEnvelope request, RpcResponder responder) {
        EventPublishRequest payload = (EventPublishRequest) request.payload();
        deliver(payload);
        responder.success("published");
    }

    private void deliver(EventPublishRequest payload) {
        var event = payload.event();
        subscribers.getOrDefault(event.topic(), Set.of()).forEach(subscriber ->
                transport.send(subscriber, new ClusterEnvelope(
                        0,
                        local.id(),
                        subscriber,
                        ClusterEventOperations.DELIVER,
                        new EventDeliverRequest(event)
                )));
    }
}
