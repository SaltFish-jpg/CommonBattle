package com.commonbattle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 轻量级进程内事件广播器。
 * 客户端同步、统计、成就和回放采集可以订阅事件，而不进入核心结算逻辑。
 */
public final class EventBus {
    private final List<Consumer<Event>> subscribers = new ArrayList<>();

    public void subscribe(Consumer<Event> subscriber) {
        subscribers.add(subscriber);
    }

    public void publish(Event event) {
        for (Consumer<Event> subscriber : List.copyOf(subscribers)) {
            subscriber.accept(event);
        }
    }
}
