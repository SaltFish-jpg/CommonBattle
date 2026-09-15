package com.commonbattle.example.cross.scene;

/**
 * 大场景分片 tick 运行统计。
 */
public record SceneShardTickStats(
        int shardCount,
        long scheduledTickJobs,
        long completedTicks,
        long failedTicks
) {
    public SceneShardTickStats {
        if (shardCount < 0 || scheduledTickJobs < 0 || completedTicks < 0 || failedTicks < 0) {
            throw new IllegalArgumentException("scene shard tick stats must not be negative");
        }
    }

    public static SceneShardTickStats empty() {
        return new SceneShardTickStats(0, 0, 0, 0);
    }
}
