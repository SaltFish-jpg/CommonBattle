package com.commonbattle.example.cross.scene;

import java.util.Objects;

/**
 * 大场景分片规划器。
 * 它把地图 chunk 先归并到较粗的 cell，再稳定映射到 shard Actor，避免路由规则散落在 RPC、准入和迁移逻辑里。
 */
public final class LargeSceneShardPlanner {
    public static final int DEFAULT_CHUNK_SIZE = 1;

    private final String sceneId;
    private final int shardCount;
    private final int chunkSize;

    public LargeSceneShardPlanner(String sceneId, int shardCount) {
        this(sceneId, shardCount, DEFAULT_CHUNK_SIZE);
    }

    public LargeSceneShardPlanner(String sceneId, int shardCount, int chunkSize) {
        if (sceneId == null || sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        this.sceneId = sceneId;
        this.shardCount = shardCount;
        this.chunkSize = chunkSize;
    }

    public LargeSceneShardRoute route(String requestedSceneId, int chunkX, int chunkY) {
        Objects.requireNonNull(requestedSceneId, "requestedSceneId");
        if (!sceneId.equals(requestedSceneId)) {
            throw new IllegalArgumentException("Scene service only hosts " + sceneId);
        }
        int cellX = Math.floorDiv(chunkX, chunkSize);
        int cellY = Math.floorDiv(chunkY, chunkSize);
        int shardIndex = Math.floorMod(cellX * 31 + cellY, shardCount);
        return new LargeSceneShardRoute(sceneId, chunkX, chunkY, cellX, cellY, shardIndex, shardCount);
    }

    public String sceneId() {
        return sceneId;
    }

    public int shardCount() {
        return shardCount;
    }

    public int chunkSize() {
        return chunkSize;
    }
}
