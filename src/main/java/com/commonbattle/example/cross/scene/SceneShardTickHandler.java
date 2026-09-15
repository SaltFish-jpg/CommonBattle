package com.commonbattle.example.cross.scene;

/**
 * 大场景分片 tick 处理器。
 * 实现方只写单分片内的场景逻辑，调度层保证同一个 shard 同一时刻只在自己的 Actor mailbox 中执行。
 */
@FunctionalInterface
public interface SceneShardTickHandler {
    void onTick(SceneShardTickContext context);
}
