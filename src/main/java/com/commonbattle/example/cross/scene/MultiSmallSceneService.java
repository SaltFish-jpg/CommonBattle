package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多小型场景承载策略。
 * 一个 Scene 服容纳多个小场景，每个 sceneId 对应一个独立 Actor，适合副本、房间和小地图。
 */
public final class MultiSmallSceneService implements SceneServiceStrategy {
    private final ActorSystem actors;
    private final ServiceDescriptor descriptor;
    private final Map<String, ActorRef> scenes = new ConcurrentHashMap<>();

    public MultiSmallSceneService(ActorSystem actors, ServiceId serviceId, ServiceEndpoint endpoint, int capacity) {
        this.actors = actors;
        this.descriptor = new ServiceDescriptor(
                serviceId,
                endpoint,
                Set.of("scene.enter", "scene.leave", "scene.message"),
                Map.of(
                        "scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name(),
                        "scene.capacity", String.valueOf(capacity)
                )
        );
    }

    public static MultiSmallSceneService create(
            ActorSystem actors,
            String region,
            String node,
            ServiceEndpoint endpoint,
            int capacity
    ) {
        return new MultiSmallSceneService(
                actors,
                ServiceId.of(ServiceKind.SCENE, region, node),
                endpoint,
                capacity
        );
    }

    @Override
    public ServiceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ScenePlacement place(String sceneId, int chunkX, int chunkY) {
        ActorRef actor = scenes.computeIfAbsent(sceneId, id -> actors.actor("scene:" + descriptor.id().node() + ":" + id));
        return new ScenePlacement(sceneId, actor, 0, 1);
    }
}
