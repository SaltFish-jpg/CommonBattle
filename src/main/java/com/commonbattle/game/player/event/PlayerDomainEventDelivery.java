package com.commonbattle.game.player.event;

import com.commonbattle.game.event.SubscriptionDecision;

import java.util.Objects;

/**
 * 玩家领域事件的一次消费上下文。
 * 订阅方可根据 decision 判断本次事件是否存在历史缺口，并决定使用快照回源或降级处理。
 */
public record PlayerDomainEventDelivery(
        PlayerDomainVersionedEvent event,
        SubscriptionDecision decision,
        boolean stale
) {
    public PlayerDomainEventDelivery {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(decision, "decision");
    }
}
