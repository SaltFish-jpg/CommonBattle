package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationSourceHook;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;

import java.util.Objects;

/**
 * 大场景 shard Agent 源端迁移清理。
 * 目录切到目标后释放源 Scene 服该 shard 的玩家归属；目标接收失败时再按快照恢复。
 */
public final class LargeSceneShardAgentMigrationSourceHook implements AgentMigrationSourceHook {
    private final LargeSceneShardService scenes;
    private final LargeSceneShardAgentSnapshotSerializer serializer;

    public LargeSceneShardAgentMigrationSourceHook(
            LargeSceneShardService scenes,
            LargeSceneShardAgentSnapshotSerializer serializer
    ) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public void sourceMoved(AgentMigrationTask task) {
        if (!AgentIdentity.SCENE.equals(task.identity().type())) {
            return;
        }
        LargeSceneShardAgentKey key = LargeSceneShardAgentKey.parse(task.identity().key());
        scenes.removeMigratedShard(key.shardIndex());
    }

    @Override
    public void rollbackRestored(AgentMigrationTask task) {
        if (!AgentIdentity.SCENE.equals(task.identity().type())) {
            return;
        }
        if (!LargeSceneShardAgentMigrationStatePacker.STATE_TYPE.equals(task.snapshot().stateType())) {
            throw new IllegalArgumentException("unsupported large scene shard migration state type: "
                    + task.snapshot().stateType());
        }
        LargeSceneShardAgentKey key = LargeSceneShardAgentKey.parse(task.identity().key());
        LargeSceneShardAgentSnapshot snapshot = serializer.deserialize(task.snapshot().stateBytes());
        if (!snapshot.sceneId().equals(key.sceneId()) || snapshot.shardIndex() != key.shardIndex()) {
            throw new IllegalArgumentException("large scene shard migration snapshot identity mismatch");
        }
        scenes.restoreMigratedShard(snapshot, task.source().actorRef());
    }
}
