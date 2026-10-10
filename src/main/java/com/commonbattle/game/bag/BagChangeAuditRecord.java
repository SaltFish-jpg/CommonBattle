package com.commonbattle.game.bag;

import java.time.Instant;
import java.util.Objects;

/**
 * 背包变更流水记录。
 * 每条记录对应一次玩家领域事件中的一个物品变更项，便于 GM 查询、异常排查和回放校验。
 */
public record BagChangeAuditRecord(
        long playerId,
        long eventRevision,
        String reason,
        String itemId,
        int before,
        int after,
        Instant occurredAt
) {
    public BagChangeAuditRecord {
        reason = Objects.requireNonNull(reason, "reason");
        itemId = Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (eventRevision <= 0) {
            throw new IllegalArgumentException("eventRevision must be positive");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (before < 0 || after < 0) {
            throw new IllegalArgumentException("item counts must not be negative");
        }
    }

    public int delta() {
        return after - before;
    }
}
