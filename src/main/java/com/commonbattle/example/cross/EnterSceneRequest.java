package com.commonbattle.example.cross;

/**
 * 玩家 Agent 请求跨服场景接管玩家的最小数据。
 */
public record EnterSceneRequest(long playerId, String sceneId) {
}
