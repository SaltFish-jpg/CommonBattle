package com.commonbattle.game.player;

import java.util.List;
import java.util.Objects;

/**
 * 单个玩家已完成业务响应的有界快照。
 * 玩家 Agent 迁移时携带它，使目标 owner 能对迁移前已经结算的重复命令重放原始结果。
 */
public record PlayerBusinessResponseSnapshot(
        long playerId,
        List<PlayerBusinessResponse> responses
) {
    public PlayerBusinessResponseSnapshot {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        Objects.requireNonNull(responses, "responses");
        responses = List.copyOf(responses);
        for (PlayerBusinessResponse response : responses) {
            Objects.requireNonNull(response, "response");
            if (response.playerId() != playerId) {
                throw new IllegalArgumentException("response playerId mismatch");
            }
        }
    }

    public static PlayerBusinessResponseSnapshot empty(long playerId) {
        return new PlayerBusinessResponseSnapshot(playerId, List.of());
    }
}
