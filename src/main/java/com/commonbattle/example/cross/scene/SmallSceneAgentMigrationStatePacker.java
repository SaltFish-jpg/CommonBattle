package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationStatePacker;

import java.util.Objects;

/**
 * 小场景 Agent 迁移快照打包器。
 * 打包动作在源场景 mailbox 中执行，因此可直接读取该 sceneId 的在场玩家集合。
 */
public final class SmallSceneAgentMigrationStatePacker implements AgentMigrationStatePacker {
    public static final String STATE_TYPE = "commonbattle.scene.small.state.v1";

    private final MultiSmallSceneService scenes;
    private final SmallSceneAgentSnapshotSerializer serializer;

    public SmallSceneAgentMigrationStatePacker(
            MultiSmallSceneService scenes,
            SmallSceneAgentSnapshotSerializer serializer
    ) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public AgentMigrationSnapshot pack(AgentIdentity identity, AgentLocation target, ActorContext context) {
        if (!AgentIdentity.SCENE.equals(identity.type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + identity.type());
        }
        SmallSceneAgentSnapshot snapshot = scenes.exportForMigrationInCurrentMailbox(identity.key());
        return new AgentMigrationSnapshot(STATE_TYPE, serializer.serialize(snapshot));
    }
}
