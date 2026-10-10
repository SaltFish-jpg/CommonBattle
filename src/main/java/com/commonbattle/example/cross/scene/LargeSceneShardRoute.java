package com.commonbattle.example.cross.scene;

/**
 * 大场景 chunk 到 shard 的稳定路由结果。
 * 业务层可以把它用于热点观测、迁移决策和跨服转发，而不需要重复理解分片哈希规则。
 */
public record LargeSceneShardRoute(
        String sceneId,
        int chunkX,
        int chunkY,
        int cellX,
        int cellY,
        int shardIndex,
        int shardCount
) {
    public LargeSceneShardRoute {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("invalid shard index");
        }
    }

    public LargeSceneShardAgentKey agentKey() {
        return new LargeSceneShardAgentKey(sceneId, shardIndex);
    }
}
