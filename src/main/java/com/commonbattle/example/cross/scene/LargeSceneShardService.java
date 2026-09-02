package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.cross.SceneOperations;

import java.util.Map;
import java.util.Set;

/**
 * 超大场景承载策略。
 * 一个 Scene 服只承载一个大场景，并把地图地块稳定映射到多个 shard Actor，避免整个大地图被单 Actor 卡住。
 */
public final class LargeSceneShardService implements SceneServiceStrategy {
    private final ServiceDescriptor descriptor;
    private final ActorRef[] shards;
    private final String sceneId;

    public LargeSceneShardService(
            ActorSystem actors,
            ServiceId serviceId,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount
    ) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.sceneId = sceneId;
        this.shards = new ActorRef[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shards[i] = actors.actor("scene:" + serviceId.node() + ":" + sceneId + ":shard-" + i);
        }
        this.descriptor = new ServiceDescriptor(
                serviceId,
                endpoint,
                Set.of(SceneOperations.ENTER, SceneOperations.LEAVE, SceneOperations.MESSAGE),
                Map.of(
                        "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                        "scene.id", sceneId,
                        "scene.shards", String.valueOf(shardCount)
                )
        );
    }

    public static LargeSceneShardService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            String sceneId,
            int shardCount
    ) {
        return new LargeSceneShardService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                sceneId,
                shardCount
        );
    }

    @Override
    public ServiceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ScenePlacement place(String requestedSceneId, int chunkX, int chunkY) {
        if (!sceneId.equals(requestedSceneId)) {
            throw new IllegalArgumentException("Scene service only hosts " + sceneId);
        }
        int shardIndex = Math.floorMod(chunkX * 31 + chunkY, shards.length);
        return new ScenePlacement(sceneId, shards[shardIndex], shardIndex, shards.length);
    }
}
