package com.commonbattle.game.player.event;

/**
 * 玩家领域事件消费统计。
 */
public record PlayerDomainEventProcessorStats(
        long appliedEvents,
        long duplicateOrOldEvents,
        long gapEvents,
        long ignoredEvents,
        int staleOwners
) {
}
