package com.commonbattle.example.cross.scene;

/**
 * Scene 内移动后的落点变化。
 */
public record SceneMoveOutcome(
        String sceneId,
        int chunkX,
        int chunkY,
        int previousShardIndex,
        int shardIndex,
        boolean moved,
        SceneAoiChangeSet aoi
) {
    public SceneMoveOutcome {
        if (aoi == null) {
            aoi = SceneAoiChangeSet.empty(-1);
        }
    }

    public SceneMoveOutcome(
            String sceneId,
            int chunkX,
            int chunkY,
            int previousShardIndex,
            int shardIndex,
            boolean moved
    ) {
        this(sceneId, chunkX, chunkY, previousShardIndex, shardIndex, moved, SceneAoiChangeSet.empty(-1));
    }

    public static SceneMoveOutcome rejected(String sceneId, int chunkX, int chunkY) {
        return new SceneMoveOutcome(sceneId, chunkX, chunkY, -1, -1, false, SceneAoiChangeSet.empty(-1));
    }
}
