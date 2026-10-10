package com.commonbattle.example.cross.scene;

/**
 * Scene shard 内产生的玩家视野/移动同步事件。
 * subjectPlayerId 表示被观察或移动的玩家，接收者由广播信封决定。
 */
public record SceneBroadcastEvent(
        SceneBroadcastEventType type,
        String sceneId,
        long subjectPlayerId,
        long sourcePlayerId,
        int shardIndex,
        int chunkX,
        int chunkY,
        int value,
        int hpAfter
) {
    public SceneBroadcastEvent(
            SceneBroadcastEventType type,
            String sceneId,
            long subjectPlayerId,
            int shardIndex,
            int chunkX,
            int chunkY
    ) {
        this(type, sceneId, subjectPlayerId, 0, shardIndex, chunkX, chunkY, 0, 0);
    }

    public SceneBroadcastEvent {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (shardIndex < 0) {
            throw new IllegalArgumentException("shardIndex must not be negative");
        }
    }
}
