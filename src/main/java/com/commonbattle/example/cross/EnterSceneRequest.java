package com.commonbattle.example.cross;

/**
 * 玩家 Agent 请求跨服场景接管玩家的最小数据。
 * chunk 坐标用于大 Scene 服按地图区域路由到正确 shard；老调用默认进入 0,0。
 */
public record EnterSceneRequest(long playerId, String sceneId, int chunkX, int chunkY) {
    public EnterSceneRequest(long playerId, String sceneId) {
        this(playerId, sceneId, 0, 0);
    }
}
