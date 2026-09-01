package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;

/**
 * 场景在 Scene 服内的 Actor 落点。
 */
public record ScenePlacement(String sceneId, ActorRef actor, int shardIndex, int shardCount) {
}
