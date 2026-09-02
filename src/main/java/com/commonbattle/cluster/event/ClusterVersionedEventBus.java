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
import java.util.Set;
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
        AutoCloseable localSubscription = listenLocal(topic, subscriber);
        subscribeRemote(topic, Set.of());
        return () -> {
            try {
                localSubscription.close();
            } finally {
                unsubscribeRemote(topic, Set.of());
            }
        };
    }

    public AutoCloseable subscribe(String topic, VersionedEventSubscriber subscriber, Set<String> ownerKeys) {
        AutoCloseable localSubscription = listenLocal(topic, subscriber);
        subscribeRemote(topic, ownerKeys);
        return () -> {
            try {
                localSubscription.close();
            } finally {
                unsubscribeRemote(topic, ownerKeys);
            }
        };
    }

    public AutoCloseable subscribe(
            String topic,
            VersionedEventSubscriber subscriber,
            Map<String, Long> knownRevisions
    ) {
        AutoCloseable subscription = subscribe(topic, subscriber, knownRevisions.keySet());
        replay(topic, knownRevisions, knownRevisions.keySet());
        return subscription;
    }

    /**
     * 仅注册本进程事件处理器，不向中心发起订阅。
     * 动态 owner 订阅组件用它保持单个本地处理器，再独立增删远端 owner 订阅。
     */
    public AutoCloseable listenLocal(String topic, VersionedEventSubscriber subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");
        localSubscribers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> localSubscribers.getOrDefault(topic, new CopyOnWriteArrayList<>()).remove(subscriber);
    }

    public void subscribeRemote(String topic, Set<String> ownerKeys) {
        Objects.requireNonNull(topic, "topic");
        Set<String> requestedOwners = Set.copyOf(Objects.requireNonNull(ownerKeys, "ownerKeys"));
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                ClusterEventOperations.SUBSCRIBE,
                new EventSubscribeRequest(local, topic, requestedOwners),
                String.class
        ), IgnoreCallback.INSTANCE);
    }

    public void unsubscribeRemote(String topic, Set<String> ownerKeys) {
        Objects.requireNonNull(topic, "topic");
        Set<String> requestedOwners = Set.copyOf(Objects.requireNonNull(ownerKeys, "ownerKeys"));
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                ClusterEventOperations.UNSUBSCRIBE,
                new EventUnsubscribeRequest(local, topic, requestedOwners),
                String.class
        ), IgnoreCallback.INSTANCE);
    }

    public void replay(String topic, Map<String, Long> knownRevisions) {
        replay(topic, knownRevisions, IgnoreReplayCallback.INSTANCE);
    }

    public void replay(String topic, Map<String, Long> knownRevisions, Set<String> ownerKeys) {
        replay(topic, knownRevisions, ownerKeys, IgnoreReplayCallback.INSTANCE);
    }

    public void replay(String topic, Map<String, Long> knownRevisions, RpcCallback<EventReplayResult> callback) {
        replay(topic, knownRevisions, knownRevisions.keySet(), callback);
    }

    public void replay(
            String topic,
            Map<String, Long> knownRevisions,
            Set<String> ownerKeys,
            RpcCallback<EventReplayResult> callback
    ) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(knownRevisions, "knownRevisions");
        Objects.requireNonNull(ownerKeys, "ownerKeys");
        gateway.call(new RpcRequest<>(
                ServiceKind.CENTER.name(),
                ClusterEventOperations.REPLAY,
                new EventReplayRequest(local, topic, Map.copyOf(knownRevisions), Set.copyOf(ownerKeys)),
                EventReplayResult.class
        ), Objects.requireNonNull(callback, "callback"));
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

    private enum IgnoreReplayCallback implements RpcCallback<EventReplayResult> {
        INSTANCE;

        @Override
        public void success(EventReplayResult response) {
        }

        @Override
        public void failure(Throwable error) {
        }
    }
}
