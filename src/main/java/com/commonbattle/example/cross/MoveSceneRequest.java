package com.commonbattle.example.cross;

/**
 * 玩家在 Scene 内移动到新 chunk 的请求。
 */
public record MoveSceneRequest(long playerId, String sceneId, int chunkX, int chunkY) {
}
