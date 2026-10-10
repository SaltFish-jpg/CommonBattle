package com.commonbattle.game.bag;

/**
 * 背包变更流水查询条件。
 */
public record BagChangeAuditQuery(
        Long playerId,
        String itemId,
        String reason,
        int offset,
        int limit
) {
    public BagChangeAuditQuery {
        if (playerId != null && playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (itemId != null && itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static BagChangeAuditQuery recent(int limit) {
        return new BagChangeAuditQuery(null, null, null, 0, limit);
    }
}
