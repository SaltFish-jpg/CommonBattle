package com.commonbattle.game.scene;

/**
 * Scene 服当前承载状态。
 * 注册中心动态 metadata 和运维健康视图可用它表达场景数、在线玩家数和分片热度。
 */
public record SceneRuntimeStats(
        int activeScenes,
        int activePlayers,
        int shardCount,
        int maxShardPlayers,
        int playerInterests,
        int allianceReferences,
        long duplicateEnters,
        long missingLeaves,
        long projectionReceivedEvents,
        long projectionAppliedEvents,
        long projectionDuplicateEvents,
        long projectionGapEvents,
        long projectionRepairRequests,
        long projectionAppliedSnapshots,
        long projectionIgnoredSnapshots,
        int projectionStaleViews
) {
    public SceneRuntimeStats(int activeScenes, int activePlayers, int shardCount, int maxShardPlayers) {
        this(activeScenes, activePlayers, shardCount, maxShardPlayers, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SceneRuntimeStats(
            int activeScenes,
            int activePlayers,
            int shardCount,
            int maxShardPlayers,
            int playerInterests,
            int allianceReferences,
            long duplicateEnters,
            long missingLeaves
    ) {
        this(activeScenes, activePlayers, shardCount, maxShardPlayers, playerInterests, allianceReferences,
                duplicateEnters, missingLeaves, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SceneRuntimeStats {
        if (activeScenes < 0 || activePlayers < 0 || shardCount < 0 || maxShardPlayers < 0
                || playerInterests < 0 || allianceReferences < 0 || duplicateEnters < 0 || missingLeaves < 0
                || projectionReceivedEvents < 0 || projectionAppliedEvents < 0 || projectionDuplicateEvents < 0
                || projectionGapEvents < 0 || projectionRepairRequests < 0 || projectionAppliedSnapshots < 0
                || projectionIgnoredSnapshots < 0 || projectionStaleViews < 0) {
            throw new IllegalArgumentException("scene runtime stats must not be negative");
        }
    }

    public static SceneRuntimeStats empty() {
        return new SceneRuntimeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public SceneRuntimeStats plus(SceneRuntimeStats other) {
        return new SceneRuntimeStats(
                activeScenes + other.activeScenes,
                activePlayers + other.activePlayers,
                shardCount + other.shardCount,
                Math.max(maxShardPlayers, other.maxShardPlayers),
                playerInterests + other.playerInterests,
                allianceReferences + other.allianceReferences,
                duplicateEnters + other.duplicateEnters,
                missingLeaves + other.missingLeaves,
                projectionReceivedEvents + other.projectionReceivedEvents,
                projectionAppliedEvents + other.projectionAppliedEvents,
                projectionDuplicateEvents + other.projectionDuplicateEvents,
                projectionGapEvents + other.projectionGapEvents,
                projectionRepairRequests + other.projectionRepairRequests,
                projectionAppliedSnapshots + other.projectionAppliedSnapshots,
                projectionIgnoredSnapshots + other.projectionIgnoredSnapshots,
                projectionStaleViews + other.projectionStaleViews
        );
    }
}
