package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.SceneRuntimeView;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Scene 服内部承载策略。
 * 小场景可一场景一个 Agent；超大场景可按地块拆成多个 Actor 分片，以便同一大场景吃满更多 CPU。
 */
public interface SceneServiceStrategy extends SceneRuntimeView {
    ServiceDescriptor descriptor();

    /**
     * 在服务开始接收流量前接入全局 Agent owner 生命周期。
     */
    default void attachLifecycles(AgentLifecycleManager lifecycles) {
        Objects.requireNonNull(lifecycles, "lifecycles");
    }

    default SceneRuntimeStats stats() {
        return SceneRuntimeStats.empty();
    }

    ScenePlacement place(String sceneId, int chunkX, int chunkY);

    /**
     * 返回跨节点 RPC 路由使用的场景 owner 身份。
     * 小场景按 scene 隔离；大场景可用一个稳定 shard 代表承载整个场景的进程。
     */
    default AgentIdentity routingIdentity(String sceneId) {
        return AgentIdentity.scene(sceneId);
    }

    /**
     * 将本策略创建的 ActorId 还原为全局 Agent 身份，供热点迁移管理入口使用。
     */
    default Optional<AgentIdentity> identityOfActor(String actorId) {
        return Optional.empty();
    }

    /**
     * 生成目标 Scene 进程内的 ActorId，避免把源节点名带入迁入邮箱。
     */
    default String migrationTargetActorId(AgentIdentity identity, ServiceId targetServiceId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(targetServiceId, "targetServiceId");
        return identity.wireName();
    }

    default ScenePlacement enter(long playerId, String sceneId, int chunkX, int chunkY) {
        return place(sceneId, chunkX, chunkY);
    }

    /**
     * 把玩家进入场景投递到目标 Scene Actor 后执行。
     * RPC/Netty 入站线程应调用本入口，避免同步修改场景状态。
     */
    default void enterAsync(
            long playerId,
            String sceneId,
            int chunkX,
            int chunkY,
            Consumer<ScenePlacement> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        try {
            callback.accept(enter(playerId, sceneId, chunkX, chunkY));
        } catch (RuntimeException e) {
            failure.accept(e);
        }
    }

    default boolean leave(long playerId, String sceneId) {
        return true;
    }

    /**
     * 把玩家离开场景投递到对应 Scene Actor 后执行。
     */
    default void leaveAsync(
            long playerId,
            String sceneId,
            Consumer<Boolean> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        try {
            callback.accept(leave(playerId, sceneId));
        } catch (RuntimeException e) {
            failure.accept(e);
        }
    }

    default SceneMoveOutcome move(long playerId, String sceneId, int chunkX, int chunkY) {
        return SceneMoveOutcome.rejected(sceneId, chunkX, chunkY);
    }

    /**
     * 把玩家移动投递到目标 Scene Actor 后执行。
     */
    default void moveAsync(
            long playerId,
            String sceneId,
            int chunkX,
            int chunkY,
            Consumer<SceneMoveOutcome> callback,
            Consumer<Throwable> failure
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(failure, "failure");
        try {
            callback.accept(move(playerId, sceneId, chunkX, chunkY));
        } catch (RuntimeException e) {
            failure.accept(e);
        }
    }
}
