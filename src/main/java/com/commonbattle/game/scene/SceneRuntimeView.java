package com.commonbattle.game.scene;

/**
 * Scene 服运行时只读观测视图。
 */
@FunctionalInterface
public interface SceneRuntimeView {
    SceneRuntimeStats stats();
}
