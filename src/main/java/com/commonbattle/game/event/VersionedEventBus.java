package com.commonbattle.game.event;

/**
 * 版本事件总线。
 * 本服可用内存实现，跨服可适配到 RPC notify、Redis Stream 或专门消息服务。
 */
public interface VersionedEventBus extends EventPublisher {
    AutoCloseable subscribe(String topic, VersionedEventSubscriber subscriber);
}
