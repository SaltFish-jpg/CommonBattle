package com.commonbattle.game.scene;

/**
 * Scene 服运行时只读观测视图。
 */
@FunctionalInterface
public interface SceneRuntimeView {
    SceneRuntimeStats stats();

    default SceneProjectionStats friendProjectionStats() {
        return SceneProjectionStats.empty();
    }

    default SceneProjectionStats allianceProjectionStats() {
        return SceneProjectionStats.empty();
    }
}
