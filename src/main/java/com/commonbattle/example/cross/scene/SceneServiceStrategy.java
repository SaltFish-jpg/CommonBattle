package com.commonbattle.example.cross.scene;

import com.commonbattle.cluster.ServiceDescriptor;

/**
 * Scene 服内部承载策略。
 * 小场景可一场景一个 Agent；超大场景可按地块拆成多个 Actor 分片，以便同一大场景吃满更多 CPU。
 */
public interface SceneServiceStrategy {
    ServiceDescriptor descriptor();

    ScenePlacement place(String sceneId, int chunkX, int chunkY);

    default ScenePlacement enter(long playerId, String sceneId, int chunkX, int chunkY) {
        return place(sceneId, chunkX, chunkY);
    }

    default boolean leave(long playerId, String sceneId) {
        return true;
    }
}
