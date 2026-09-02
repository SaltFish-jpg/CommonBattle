package com.commonbattle.example.cross;

/**
 * 玩家离开跨服场景结果。
 */
public record LeaveSceneResult(long playerId, String sceneId, boolean left) {
}
