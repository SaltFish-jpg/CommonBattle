package com.commonbattle.example.cross;

/**
 * 玩家离开跨服场景请求。
 */
public record LeaveSceneRequest(long playerId, String sceneId) {
}
