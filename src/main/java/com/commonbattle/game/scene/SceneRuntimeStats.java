package com.commonbattle.game.scene;

/**
 * Scene 服当前承载状态。
 * 注册中心动态 metadata 和运维健康视图可用它表达场景数、在线玩家数和分片热度。
 */
public record SceneRuntimeStats(
        int activeScenes,
        int activePlayers,
        int shardCount,
        int maxShardPlayers
) {
    public SceneRuntimeStats {
        if (activeScenes < 0 || activePlayers < 0 || shardCount < 0 || maxShardPlayers < 0) {
            throw new IllegalArgumentException("scene runtime stats must not be negative");
        }
    }

    public static SceneRuntimeStats empty() {
        return new SceneRuntimeStats(0, 0, 0, 0);
    }

    public SceneRuntimeStats plus(SceneRuntimeStats other) {
        return new SceneRuntimeStats(
                activeScenes + other.activeScenes,
                activePlayers + other.activePlayers,
                shardCount + other.shardCount,
                Math.max(maxShardPlayers, other.maxShardPlayers)
        );
    }
}
