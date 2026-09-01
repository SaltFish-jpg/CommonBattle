package com.commonbattle.example.cross;

/**
 * Zone Scene 接受玩家进入后的回包。
 */
public record EnterSceneResult(long playerId, String sceneId, long sceneActorId) {
}
