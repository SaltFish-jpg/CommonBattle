package com.commonbattle.observability;

import com.commonbattle.game.scene.SceneRuntimeStats;

/**
 * Scene 服运行时聚合健康统计。
 */
public record SceneRuntimeHealthStats(
        int runtimeCount,
        int activeScenes,
        int activePlayers,
        int shardCount,
        int maxShardPlayers,
        int playerInterests,
        int allianceReferences,
        long duplicateEnters,
        long missingLeaves
) {
    public static SceneRuntimeHealthStats empty() {
        return new SceneRuntimeHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static SceneRuntimeHealthStats from(int runtimeCount, SceneRuntimeStats stats) {
        return new SceneRuntimeHealthStats(
                runtimeCount,
                stats.activeScenes(),
                stats.activePlayers(),
                stats.shardCount(),
                stats.maxShardPlayers(),
                stats.playerInterests(),
                stats.allianceReferences(),
                stats.duplicateEnters(),
                stats.missingLeaves()
        );
    }
}
