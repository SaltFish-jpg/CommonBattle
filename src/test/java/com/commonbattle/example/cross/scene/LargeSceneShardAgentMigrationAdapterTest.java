package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStatus;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeSceneShardAgentMigrationAdapterTest {
    private final ProtoLargeSceneShardAgentSnapshotSerializer serializer =
            new ProtoLargeSceneShardAgentSnapshotSerializer();

    @Test
    void packerSerializesOnlyRequestedShardSnapshot() {
        Fixture fixture = new Fixture("scene-1");
        fixture.scenes.enter(10001L, "world-1", 0, 0);
        fixture.scenes.enter(10002L, "world-1", 0, 0);
        fixture.scenes.enter(10003L, "world-1", 1, 0);
        LargeSceneShardAgentMigrationStatePacker packer =
                new LargeSceneShardAgentMigrationStatePacker(fixture.scenes, serializer);

        AgentMigrationSnapshot migration = packer.pack(
                AgentIdentity.scene(new LargeSceneShardAgentKey("world-1", 0).wireName()),
                new AgentLocation(ServiceId.of(ServiceKind.SCENE, "r1", "scene-2"),
                        new ActorRef("scene:scene-2:world-1:shard-0")),
                null
        );
        LargeSceneShardAgentSnapshot snapshot = serializer.deserialize(migration.stateBytes());

        assertEquals(LargeSceneShardAgentMigrationStatePacker.STATE_TYPE, migration.stateType());
        assertEquals("world-1", snapshot.sceneId());
        assertEquals(0, snapshot.shardIndex());
        assertEquals(Set.of(10001L, 10002L), snapshot.players());
    }

    @Test
    void sourceHookRemovesShardAndRestoresItAfterRollback() {
        Fixture fixture = new Fixture("scene-1");
        fixture.scenes.enter(10001L, "world-1", 0, 0);
        fixture.scenes.enter(10002L, "world-1", 1, 0);
        LargeSceneShardAgentMigrationStatePacker packer =
                new LargeSceneShardAgentMigrationStatePacker(fixture.scenes, serializer);
        AgentIdentity identity = AgentIdentity.scene(new LargeSceneShardAgentKey("world-1", 0).wireName());
        AgentMigrationSnapshot snapshot = packer.pack(
                identity,
                new AgentLocation(ServiceId.of(ServiceKind.SCENE, "r1", "scene-2"),
                        new ActorRef("scene:scene-2:world-1:shard-0")),
                null
        );
        AgentMigrationTask task = new AgentMigrationTask(
                "migration-large-shard-1",
                identity,
                new AgentLocation(fixture.serviceId, new ActorRef(fixture.scenes.actorId(0))),
                new AgentLocation(ServiceId.of(ServiceKind.SCENE, "r1", "scene-2"),
                        new ActorRef("scene:scene-2:world-1:shard-0")),
                snapshot,
                AgentMigrationTaskStatus.MOVED,
                "",
                Instant.parse("2026-09-01T00:00:00Z")
        );
        LargeSceneShardAgentMigrationSourceHook hook =
                new LargeSceneShardAgentMigrationSourceHook(fixture.scenes, serializer);

        hook.sourceMoved(task);

        assertTrue(fixture.scenes.shardPlayers(0).isEmpty());
        assertEquals(Set.of(10002L), fixture.scenes.shardPlayers(3));

        hook.rollbackRestored(task);

        assertEquals(Set.of(10001L), fixture.scenes.shardPlayers(0));
        assertEquals(Set.of(10002L), fixture.scenes.shardPlayers(3));
    }

    private static final class Fixture {
        private final ActorSystem actors = new ActorSystem(Runnable::run, 64);
        private final ServiceId serviceId;
        private final LargeSceneShardService scenes;

        private Fixture(String node) {
            serviceId = ServiceId.of(ServiceKind.SCENE, "r1", node);
            scenes = new LargeSceneShardService(
                    actors,
                    serviceId,
                    new ServiceEndpoint("127.0.0.1", 9200),
                    "world-1",
                    4
            );
        }
    }
}
