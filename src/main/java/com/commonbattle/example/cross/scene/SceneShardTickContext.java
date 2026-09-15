package com.commonbattle.example.cross.scene;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 大场景分片 tick 上下文。
 * 业务在分片 Actor 邮箱内读取该快照，做 AOI、怪物 AI、区域事件等局部结算。
 */
public record SceneShardTickContext(
        String sceneId,
        int shardIndex,
        int shardCount,
        Set<Long> players,
        Instant tickAt
) {
    public SceneShardTickContext {
        Objects.requireNonNull(sceneId, "sceneId");
        players = Set.copyOf(Objects.requireNonNull(players, "players"));
        Objects.requireNonNull(tickAt, "tickAt");
        if (sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (shardIndex < 0 || shardCount <= 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("invalid shard index");
        }
    }
}
