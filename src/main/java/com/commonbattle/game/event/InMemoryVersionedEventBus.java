package com.commonbattle.game.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版本事件总线。
 * 适合单服或测试环境，生产跨服部署时由网络事件通道替换。
 */
public final class InMemoryVersionedEventBus implements VersionedEventBus {
    private final Map<String, CopyOnWriteArrayList<VersionedEventSubscriber>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(VersionedEvent event) {
        Objects.requireNonNull(event, "event");
        subscribers.getOrDefault(event.topic(), new CopyOnWriteArrayList<>())
                .forEach(subscriber -> subscriber.onEvent(event));
    }

    @Override
    public AutoCloseable subscribe(String topic, VersionedEventSubscriber subscriber) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>()).remove(subscriber);
    }

    public List<VersionedEventSubscriber> subscribers(String topic) {
        return List.copyOf(subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>()));
    }
}
