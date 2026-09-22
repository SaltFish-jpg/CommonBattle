package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationStatePacker;

import java.util.Objects;

/**
 * 大场景 shard Agent 迁移快照打包器。
 * 打包动作在源 shard mailbox 中执行，读取该分片内一致的玩家集合。
 */
public final class LargeSceneShardAgentMigrationStatePacker implements AgentMigrationStatePacker {
    public static final String STATE_TYPE = "commonbattle.scene.large-shard.state.v1";

    private final LargeSceneShardService scenes;
    private final LargeSceneShardAgentSnapshotSerializer serializer;

    public LargeSceneShardAgentMigrationStatePacker(
            LargeSceneShardService scenes,
            LargeSceneShardAgentSnapshotSerializer serializer
    ) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public AgentMigrationSnapshot pack(AgentIdentity identity, AgentLocation target, ActorContext context) {
        if (!AgentIdentity.SCENE.equals(identity.type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + identity.type());
        }
        LargeSceneShardAgentKey key = LargeSceneShardAgentKey.parse(identity.key());
        LargeSceneShardAgentSnapshot snapshot = scenes.exportShardForMigrationInCurrentMailbox(key.shardIndex());
        if (!snapshot.sceneId().equals(key.sceneId())) {
            throw new IllegalArgumentException("large scene shard identity mismatch");
        }
        return new AgentMigrationSnapshot(STATE_TYPE, serializer.serialize(snapshot));
    }
}
