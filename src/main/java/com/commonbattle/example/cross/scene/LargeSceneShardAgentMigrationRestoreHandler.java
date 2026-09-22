package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptRequest;
import com.commonbattle.actor.agent.migration.AgentMigrationRestoreHandler;

import java.util.Objects;

/**
 * 大场景 shard Agent 迁入恢复器。
 * 由目标 shard mailbox 执行，恢复后该 shard 继续独立串行处理。
 */
public final class LargeSceneShardAgentMigrationRestoreHandler implements AgentMigrationRestoreHandler {
    private final LargeSceneShardService scenes;
    private final LargeSceneShardAgentSnapshotSerializer serializer;

    public LargeSceneShardAgentMigrationRestoreHandler(
            LargeSceneShardService scenes,
            LargeSceneShardAgentSnapshotSerializer serializer
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
        if (!LargeSceneShardAgentMigrationStatePacker.STATE_TYPE.equals(request.stateType())) {
            throw new IllegalArgumentException("unsupported large scene shard migration state type: "
                    + request.stateType());
        }
        LargeSceneShardAgentKey key = LargeSceneShardAgentKey.parse(request.identity().key());
        LargeSceneShardAgentSnapshot snapshot = serializer.deserialize(request.stateBytes());
        if (!snapshot.sceneId().equals(key.sceneId()) || snapshot.shardIndex() != key.shardIndex()) {
            throw new IllegalArgumentException("large scene shard migration snapshot identity mismatch");
        }
        scenes.restoreMigratedShard(snapshot, context.self());
    }
}
