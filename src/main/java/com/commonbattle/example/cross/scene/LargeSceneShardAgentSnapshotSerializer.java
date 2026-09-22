package com.commonbattle.example.cross.scene;

/**
 * 大场景 shard 迁移快照序列化器。
 */
public interface LargeSceneShardAgentSnapshotSerializer {
    byte[] serialize(LargeSceneShardAgentSnapshot snapshot);

    LargeSceneShardAgentSnapshot deserialize(byte[] bytes);
}
