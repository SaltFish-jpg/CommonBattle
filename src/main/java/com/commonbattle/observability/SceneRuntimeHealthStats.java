package com.commonbattle.observability;

import com.commonbattle.game.scene.SceneProjectionStats;
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
        int pendingShardTransfers,
        long failedShardTransfers,
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
        int projectionStaleViews,
        long projectionFreshReadRequests,
        long projectionFreshReadLocalHits,
        long projectionFreshReadRefreshes,
        long projectionFreshReadRemoteStale,
        long projectionFreshReadRemoteMisses,
        SceneProjectionStats friendProjection,
        SceneProjectionStats allianceProjection
) {
    public static SceneRuntimeHealthStats empty() {
        return new SceneRuntimeHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                SceneProjectionStats.empty(), SceneProjectionStats.empty());
    }

    public static SceneRuntimeHealthStats from(int runtimeCount, SceneRuntimeStats stats) {
        return from(runtimeCount, stats, SceneProjectionStats.empty(), SceneProjectionStats.empty());
    }

    public static SceneRuntimeHealthStats from(
            int runtimeCount,
            SceneRuntimeStats stats,
            SceneProjectionStats friendProjection,
            SceneProjectionStats allianceProjection
    ) {
        return new SceneRuntimeHealthStats(
                runtimeCount,
                stats.activeScenes(),
                stats.activePlayers(),
                stats.shardCount(),
                stats.maxShardPlayers(),
                stats.pendingShardTransfers(),
                stats.failedShardTransfers(),
                stats.playerInterests(),
                stats.allianceReferences(),
                stats.duplicateEnters(),
                stats.missingLeaves(),
                stats.projectionReceivedEvents(),
                stats.projectionAppliedEvents(),
                stats.projectionDuplicateEvents(),
                stats.projectionGapEvents(),
                stats.projectionRepairRequests(),
                stats.projectionAppliedSnapshots(),
                stats.projectionIgnoredSnapshots(),
                stats.projectionStaleViews(),
                stats.projectionFreshReadRequests(),
                stats.projectionFreshReadLocalHits(),
                stats.projectionFreshReadRefreshes(),
                stats.projectionFreshReadRemoteStale(),
                stats.projectionFreshReadRemoteMisses(),
                friendProjection,
                allianceProjection
        );
    }
}
