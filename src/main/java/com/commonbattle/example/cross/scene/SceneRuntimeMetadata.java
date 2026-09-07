package com.commonbattle.example.cross.scene;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.observability.RuntimeHealthPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * Scene 服动态 metadata 约定。
 * 这些字段用于注册中心订阅方做负载展示、路由调试和容量告警，不改变 RPC 协议。
 */
public final class SceneRuntimeMetadata {
    public static final String ACTIVE_SCENES = "scene.active.scenes";
    public static final String ACTIVE_PLAYERS = "scene.active.players";
    public static final String MAX_SHARD_PLAYERS = "scene.max.shard.players";
    public static final String CAPACITY_STATUS = "scene.capacity.status";
    public static final String CAPACITY_REASON = "scene.capacity.reason";
    public static final String CAPACITY_OK = "OK";
    public static final String CAPACITY_DEGRADED = "DEGRADED";

    private SceneRuntimeMetadata() {
    }

    public static ServiceDescriptor apply(ServiceDescriptor descriptor, SceneRuntimeStats stats) {
        Map<String, String> metadata = new HashMap<>(descriptor.metadata());
        metadata.put(ACTIVE_SCENES, String.valueOf(stats.activeScenes()));
        metadata.put(ACTIVE_PLAYERS, String.valueOf(stats.activePlayers()));
        metadata.put(MAX_SHARD_PLAYERS, String.valueOf(stats.maxShardPlayers()));
        return descriptor.withMetadata(metadata);
    }

    public static ServiceDescriptor apply(ServiceDescriptor descriptor, SceneRuntimeStats stats, RuntimeHealthPolicy policy) {
        Map<String, String> metadata = new HashMap<>(apply(descriptor, stats).metadata());
        String reason = capacityReason(stats, policy);
        metadata.put(CAPACITY_STATUS, reason.isBlank() ? CAPACITY_OK : CAPACITY_DEGRADED);
        if (reason.isBlank()) {
            metadata.remove(CAPACITY_REASON);
        } else {
            metadata.put(CAPACITY_REASON, reason);
        }
        return descriptor.withMetadata(metadata);
    }

    public static boolean capacityDegraded(ServiceDescriptor descriptor) {
        return CAPACITY_DEGRADED.equals(descriptor.metadata(CAPACITY_STATUS));
    }

    private static String capacityReason(SceneRuntimeStats stats, RuntimeHealthPolicy policy) {
        if (exceedsEnabledLimit(stats.activeScenes(), policy.maxSceneActiveScenes())) {
            return ACTIVE_SCENES;
        }
        if (exceedsEnabledLimit(stats.activePlayers(), policy.maxSceneActivePlayers())) {
            return ACTIVE_PLAYERS;
        }
        if (exceedsEnabledLimit(stats.maxShardPlayers(), policy.maxSceneShardHotspotPlayers())) {
            return MAX_SHARD_PLAYERS;
        }
        return "";
    }

    private static boolean exceedsEnabledLimit(int value, int limit) {
        return limit > 0 && value > limit;
    }
}
