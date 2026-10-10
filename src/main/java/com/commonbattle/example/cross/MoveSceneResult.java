package com.commonbattle.example.cross;

import java.util.List;

/**
 * Scene 内移动结果。
 * previousShardIndex 和 shardIndex 用于观测跨 shard 移动；未移动时二者为 -1。
 */
public record MoveSceneResult(
        long playerId,
        String sceneId,
        int chunkX,
        int chunkY,
        int previousShardIndex,
        int shardIndex,
        boolean moved,
        List<Long> visibleEnter,
        List<Long> visibleLeave,
        List<Long> observerEnter,
        List<Long> observerLeave
) {
    public MoveSceneResult {
        visibleEnter = List.copyOf(visibleEnter);
        visibleLeave = List.copyOf(visibleLeave);
        observerEnter = List.copyOf(observerEnter);
        observerLeave = List.copyOf(observerLeave);
    }

    public MoveSceneResult(
            long playerId,
            String sceneId,
            int chunkX,
            int chunkY,
            int previousShardIndex,
            int shardIndex,
            boolean moved
    ) {
        this(playerId, sceneId, chunkX, chunkY, previousShardIndex, shardIndex, moved,
                List.of(), List.of(), List.of(), List.of());
    }
}
