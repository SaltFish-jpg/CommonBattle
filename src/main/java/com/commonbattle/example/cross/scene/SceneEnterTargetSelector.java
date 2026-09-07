package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.ServiceMetadata;
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;
import com.commonbattle.cluster.rpc.RpcTargetSelector;
import com.commonbattle.example.cross.EnterSceneRequest;
import com.commonbattle.example.cross.SceneOperations;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Scene 进入请求的专用选路器。
 * 大场景按 scene.id 精确匹配，小场景按可用容量和服务负载选择，避免把玩家路由到不承载目标地图或已满的 Scene 服。
 */
public final class SceneEnterTargetSelector implements RpcTargetSelector {
    @Override
    public boolean supports(RpcRequest<?> request, ServiceKind kind) {
        return kind == ServiceKind.SCENE
                && SceneOperations.ENTER.equals(request.operation())
                && request.payload() instanceof EnterSceneRequest;
    }

    @Override
    public ServiceDescriptor select(RpcRequest<?> request, List<ServiceDescriptor> candidates) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");
        EnterSceneRequest payload = (EnterSceneRequest) request.payload();
        List<ServiceDescriptor> matchingLargeScenes = candidates.stream()
                .filter(this::largeScene)
                .filter(service -> payload.sceneId().equals(service.metadata("scene.id")))
                .toList();
        List<ServiceDescriptor> largeSceneCandidates = matchingLargeScenes.stream()
                .filter(service -> !SceneRuntimeMetadata.capacityDegraded(service))
                .toList();
        if (!largeSceneCandidates.isEmpty()) {
            return largeSceneCandidates.stream()
                    .min(Comparator.comparingInt(this::maxShardPlayers)
                            .thenComparingLong(ServiceDescriptor::loadScore)
                            .thenComparing(service -> service.id().wireName()))
                    .orElseThrow();
        }
        if (!matchingLargeScenes.isEmpty()) {
            throw new RpcNoRoutableServiceException(ServiceKind.SCENE, request.operation());
        }
        List<ServiceDescriptor> smallSceneCandidates = candidates.stream()
                .filter(this::smallScene)
                .filter(service -> !SceneRuntimeMetadata.capacityDegraded(service))
                .filter(this::hasSmallSceneCapacity)
                .toList();
        if (!smallSceneCandidates.isEmpty()) {
            return smallSceneCandidates.stream()
                    .min(Comparator.comparingLong(ServiceDescriptor::loadScore)
                            .thenComparingInt(this::activePlayers)
                            .thenComparing(service -> service.id().wireName()))
                    .orElseThrow();
        }
        throw new RpcNoRoutableServiceException(ServiceKind.SCENE, request.operation());
    }

    private boolean largeScene(ServiceDescriptor service) {
        return SceneHostingMode.LARGE_SCENE_SHARD.name().equals(service.metadata("scene.mode"));
    }

    private boolean smallScene(ServiceDescriptor service) {
        return SceneHostingMode.MULTI_SMALL_SCENE.name().equals(service.metadata("scene.mode"));
    }

    private boolean hasSmallSceneCapacity(ServiceDescriptor service) {
        int activeScenes = integer(service.metadata(SceneRuntimeMetadata.ACTIVE_SCENES), 0);
        int capacity = integer(service.metadata("scene.capacity"), Integer.MAX_VALUE);
        return activeScenes < capacity;
    }

    private int activePlayers(ServiceDescriptor service) {
        return integer(service.metadata(SceneRuntimeMetadata.ACTIVE_PLAYERS), 0);
    }

    private int maxShardPlayers(ServiceDescriptor service) {
        return integer(service.metadata(SceneRuntimeMetadata.MAX_SHARD_PLAYERS), 0);
    }

    private int integer(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }
}
