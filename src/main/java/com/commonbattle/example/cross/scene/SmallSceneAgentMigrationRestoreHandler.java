package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptRequest;
import com.commonbattle.actor.agent.migration.AgentMigrationRestoreHandler;

import java.util.Objects;

/**
 * 小场景 Agent 迁入恢复器。
 * 由目标场景 Actor mailbox 执行，恢复后该 sceneId 继续保持单 mailbox 串行语义。
 */
public final class SmallSceneAgentMigrationRestoreHandler implements AgentMigrationRestoreHandler {
    private final MultiSmallSceneService scenes;
    private final SmallSceneAgentSnapshotSerializer serializer;

    public SmallSceneAgentMigrationRestoreHandler(
            MultiSmallSceneService scenes,
            SmallSceneAgentSnapshotSerializer serializer
    ) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public void restore(AgentMigrationAcceptRequest request, ActorContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        if (!AgentIdentity.SCENE.equals(request.identity().type())) {
            throw new IllegalArgumentException("unsupported migration identity: " + request.identity().type());
        }
        if (!SmallSceneAgentMigrationStatePacker.STATE_TYPE.equals(request.stateType())) {
            throw new IllegalArgumentException("unsupported small scene migration state type: "
                    + request.stateType());
        }
        SmallSceneAgentSnapshot snapshot = serializer.deserialize(request.stateBytes());
        if (!snapshot.sceneId().equals(request.identity().key())) {
            throw new IllegalArgumentException("small scene migration snapshot identity mismatch");
        }
        scenes.restoreMigrated(snapshot, context.self());
    }
}
