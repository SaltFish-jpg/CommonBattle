package com.commonbattle.game.event;

/**
 * 版本事件发布接口。
 * 本服可直接投递，跨服可适配到 RPC notify 或消息总线。
 */
@FunctionalInterface
public interface EventPublisher {
    void publish(VersionedEvent event);
}
