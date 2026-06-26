package com.commonbattle.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lightweight in-process event broadcaster.
 * Client sync, statistics, achievements, and replay collectors can subscribe without joining core settlement logic.
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
