package com.commonbattle.example.cross.scene;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.game.scene.SceneRuntimeStats;
import com.commonbattle.game.scene.SceneRuntimeView;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Scene 服内部承载策略。
 * 小场景可一场景一个 Agent；超大场景可按地块拆成多个 Actor 分片，以便同一大场景吃满更多 CPU。
 */
public interface SceneServiceStrategy extends SceneRuntimeView {
    ServiceDescriptor descriptor();

    default SceneRuntimeStats stats() {
        return SceneRuntimeStats.empty();
    }

    ScenePlacement place(String sceneId, int chunkX, int chunkY);

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
