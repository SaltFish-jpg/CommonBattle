package com.commonbattle.example.cross.scene;

import java.util.Set;

/**
 * 大场景 shard Agent 迁移快照。
 * 一个 shard 是大场景压榨 CPU 的迁移颗粒，只包含该分片当前承载的玩家集合。
 */
public record LargeSceneShardAgentSnapshot(
        String sceneId,
        int shardIndex,
        int shardCount,
        Set<Long> players
) {
    public LargeSceneShardAgentSnapshot {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (shardIndex < 0) {
            throw new IllegalArgumentException("shardIndex must not be negative");
        }
        if (shardCount <= 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("invalid shard index");
        }
        players = Set.copyOf(players);
    }
}
