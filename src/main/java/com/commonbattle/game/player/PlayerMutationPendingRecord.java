package com.commonbattle.game.player;

import java.util.List;

/**
 * 已提交但尚未移交可靠 outbox 的玩家事件摘要，供健康检查和运维定位使用。
 */
public record PlayerMutationPendingRecord(
        long playerId,
        long stateRevision,
        long eventRevision,
        int pendingEvents,
        long firstPendingRevision,
        long lastPendingRevision,
        List<String> eventTypes
) {
    public PlayerMutationPendingRecord {
        eventTypes = List.copyOf(eventTypes);
    }
}
