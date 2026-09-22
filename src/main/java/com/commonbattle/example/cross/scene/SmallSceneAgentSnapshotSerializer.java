package com.commonbattle.example.cross.scene;

/**
 * 小场景迁移快照序列化器。
 */
public interface SmallSceneAgentSnapshotSerializer {
    byte[] serialize(SmallSceneAgentSnapshot snapshot);

    SmallSceneAgentSnapshot deserialize(byte[] bytes);
}
