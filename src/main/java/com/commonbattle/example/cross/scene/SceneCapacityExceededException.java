package com.commonbattle.example.cross.scene;

/**
 * Scene 服本地容量不足时抛出的明确业务拒绝。
 */
public final class SceneCapacityExceededException extends RuntimeException {
    public SceneCapacityExceededException(String serviceNode, int capacity) {
        super("Scene service " + serviceNode + " capacity exceeded: " + capacity);
    }
}
