package com.commonbattle.game.session;

/**
 * 玩家命令审计查询条件。
 */
public record PlayerCommandAuditQuery(
        Long playerId,
        String operation,
        PlayerCommandStatus dispatchStatus,
        PlayerCommandAuditOutcome outcome,
        int offset,
        int limit
) {
    public PlayerCommandAuditQuery {
        if (playerId != null && playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (operation != null && operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static PlayerCommandAuditQuery recent(int limit) {
        return new PlayerCommandAuditQuery(null, null, null, null, 0, limit);
    }
}
