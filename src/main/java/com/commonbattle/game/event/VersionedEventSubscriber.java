package com.commonbattle.game.event;

/**
 * 版本事件订阅者。
 */
@FunctionalInterface
public interface VersionedEventSubscriber {
    void onEvent(VersionedEvent event);
}
