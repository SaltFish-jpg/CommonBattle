package com.commonbattle.game.player;

import java.util.Objects;

/**
 * 玩家迁移运行态载荷。
 * 持久业务状态与短期幂等响应分开建模，避免把网关缓存混入长期玩家存档。
 */
public record PlayerAgentMigrationPayload(
        PlayerStateSnapshot state,
        PlayerBusinessResponseSnapshot completedResponses
) {
    public PlayerAgentMigrationPayload {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(completedResponses, "completedResponses");
        if (state.playerId() != completedResponses.playerId()) {
            throw new IllegalArgumentException("migration payload playerId mismatch");
        }
    }
}
