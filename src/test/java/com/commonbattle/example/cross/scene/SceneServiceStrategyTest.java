package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.cluster.ServiceEndpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SceneServiceStrategyTest {
    @Test
    void smallSceneServiceUsesOneActorPerScene() {
        try (ActorSystem actors = new ActorSystem(1)) {
            MultiSmallSceneService service = MultiSmallSceneService.create(
                    actors,
                    "r1",
                    "scene-small-1",
                    new ServiceEndpoint("127.0.0.1", 9100),
                    200
            );

            ScenePlacement first = service.place("room-1", 0, 0);
            ScenePlacement same = service.place("room-1", 9, 9);
            ScenePlacement other = service.place("room-2", 0, 0);

            assertEquals(first.actor(), same.actor());
            assertNotEquals(first.actor(), other.actor());
            assertEquals(SceneHostingMode.MULTI_SMALL_SCENE.name(), service.descriptor().metadata("scene.mode"));
        }
    }

    @Test
    void largeSceneServiceSplitsChunksAcrossShardActors() {
        try (ActorSystem actors = new ActorSystem(1)) {
            LargeSceneShardService service = LargeSceneShardService.create(
                    actors,
                    "r1",
                    "scene-large-1",
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    8
            );

            ScenePlacement first = service.place("world-1", 10, 20);
            ScenePlacement same = service.place("world-1", 10, 20);

            assertEquals(first, same);
            assertEquals(8, first.shardCount());
            assertEquals(SceneHostingMode.LARGE_SCENE_SHARD.name(), service.descriptor().metadata("scene.mode"));
        }
    }
}
