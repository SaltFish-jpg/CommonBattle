package com.commonbattle.game.player.event;

/**
 * 玩家领域事件消费处理器。
 * Scene、Chat、排行榜等订阅方按 eventType 注册处理逻辑，处理器必须自行保证幂等或只依赖框架过滤后的事件。
 */
@FunctionalInterface
public interface PlayerDomainEventHandler {
    void handle(PlayerDomainEventDelivery delivery);
}
