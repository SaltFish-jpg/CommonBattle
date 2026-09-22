package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationSourceHook;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;

import java.util.Objects;

/**
 * 小场景 Agent 源端迁移清理。
 * 目录切到目标后释放源 Scene 服本地场景；目标接收失败回滚时再按迁移快照恢复。
 */
public final class SmallSceneAgentMigrationSourceHook implements AgentMigrationSourceHook {
    private final MultiSmallSceneService scenes;
    private final SmallSceneAgentSnapshotSerializer serializer;

    public SmallSceneAgentMigrationSourceHook(
            MultiSmallSceneService scenes,
            SmallSceneAgentSnapshotSerializer serializer
    ) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public void sourceMoved(AgentMigrationTask task) {
        if (!AgentIdentity.SCENE.equals(task.identity().type())) {
            return;
        }
        scenes.removeMigrated(task.identity().key());
    }

    @Override
    public void rollbackRestored(AgentMigrationTask task) {
        if (!AgentIdentity.SCENE.equals(task.identity().type())) {
            return;
        }
        if (!SmallSceneAgentMigrationStatePacker.STATE_TYPE.equals(task.snapshot().stateType())) {
            throw new IllegalArgumentException("unsupported small scene migration state type: "
                    + task.snapshot().stateType());
        }
        SmallSceneAgentSnapshot snapshot = serializer.deserialize(task.snapshot().stateBytes());
        if (!snapshot.sceneId().equals(task.identity().key())) {
            throw new IllegalArgumentException("small scene migration snapshot identity mismatch");
        }
        scenes.restoreMigrated(snapshot, task.source().actorRef());
    }
}
