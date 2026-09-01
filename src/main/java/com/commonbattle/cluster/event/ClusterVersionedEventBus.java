package com.commonbattle.cluster.event;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.event.VersionedEventBus;
import com.commonbattle.game.event.VersionedEventSubscriber;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 跨服版本事件总线客户端。
 * 本地订阅者接收 event.deliver；发布和订阅请求通过中心事件服务 RPC 完成。
 */
public final class ClusterVersionedEventBus implements VersionedEventBus {
    private final ServiceId local;
    private final ClusterRpcGateway gateway;
    private final Map<String, CopyOnWriteArrayList<VersionedEventSubscriber>> localSubscribers = new ConcurrentHashMap<>();

    public ClusterVersionedEventBus(ServiceId local, ClusterRpcGateway gateway) {
        this.local = Objects.requireNonNull(local, "local");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.gateway.handle(ClusterEventOperations.DELIVER, (request, responder) -> {
            EventDeliverRequest payload = (EventDeliverRequest) request.payload();
            deliverLocal(payload.event());
            responder.success("received");
        });
    }

    @Override
    public void publish(VersionedEvent event) {
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                ClusterEventOperations.PUBLISH,
                new EventPublishRequest(event),
                String.class
        ), IgnoreCallback.INSTANCE);
    }

    @Override
    public AutoCloseable subscribe(String topic, VersionedEventSubscriber subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");
        localSubscribers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                ClusterEventOperations.SUBSCRIBE,
                new EventSubscribeRequest(local, topic),
                String.class
        ), IgnoreCallback.INSTANCE);
        return () -> {
            localSubscribers.getOrDefault(topic, new CopyOnWriteArrayList<>()).remove(subscriber);
            gateway.call(new RpcRequest<>(
                    ServiceKind.CENTER.name(),
                    ClusterEventOperations.UNSUBSCRIBE,
                    new EventUnsubscribeRequest(local, topic),
                    String.class
            ), IgnoreCallback.INSTANCE);
        };
    }

    private void deliverLocal(VersionedEvent event) {
        localSubscribers.getOrDefault(event.topic(), new CopyOnWriteArrayList<>())
                .forEach(subscriber -> subscriber.onEvent(event));
    }

    private enum IgnoreCallback implements RpcCallback<String> {
        INSTANCE;

        @Override
        public void success(String response) {
        }

        @Override
        public void failure(Throwable error) {
        }
    }
}
