package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.migration.AgentMigrationAcceptRequest;
import com.commonbattle.actor.agent.migration.AgentMigrationSnapshot;
import com.commonbattle.actor.agent.migration.AgentMigrationTask;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStatus;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallSceneAgentMigrationAdapterTest {
    private final ProtoSmallSceneAgentSnapshotSerializer serializer =
            new ProtoSmallSceneAgentSnapshotSerializer();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void packerSerializesSmallSceneSnapshot() {
        Fixture fixture = new Fixture("scene-1");
        fixture.scenes.enter(10001L, "room-1", 0, 0);
        fixture.scenes.enter(10002L, "room-1", 0, 0);
        SmallSceneAgentMigrationStatePacker packer =
                new SmallSceneAgentMigrationStatePacker(fixture.scenes, serializer);

        AgentMigrationSnapshot migration = packer.pack(
                AgentIdentity.scene("room-1"),
                new AgentLocation(ServiceId.of(ServiceKind.SCENE, "r1", "scene-2"),
                        new ActorRef("scene:scene-2:room-1")),
                null
        );
        SmallSceneAgentSnapshot snapshot = serializer.deserialize(migration.stateBytes());

        assertEquals(SmallSceneAgentMigrationStatePacker.STATE_TYPE, migration.stateType());
        assertEquals("room-1", snapshot.sceneId());
        assertEquals(Set.of(10001L, 10002L), snapshot.players());
    }

    @Test
    void restoreHandlerMountsSceneOnTargetMailbox() {
        Fixture fixture = new Fixture("scene-2");
        SmallSceneAgentSnapshot snapshot = new SmallSceneAgentSnapshot("room-1", Set.of(10001L, 10002L));
        AgentMigrationAcceptRequest request = new AgentMigrationAcceptRequest(
                "migration-scene-1",
                AgentIdentity.scene("room-1"),
                "scene:scene-2:room-1",
                SmallSceneAgentMigrationStatePacker.STATE_TYPE,
                serializer.serialize(snapshot)
        );
        SmallSceneAgentMigrationRestoreHandler handler =
                new SmallSceneAgentMigrationRestoreHandler(fixture.scenes, serializer);
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(fixture.serviceId, fixture.actors, directory,
                CLOCK);
        directory.claim(AgentIdentity.scene("room-1"),
                new AgentLocation(fixture.serviceId, new ActorRef("scene:scene-2:room-1")));

        lifecycles.acceptMigrated(AgentIdentity.scene("room-1"), "scene:scene-2:room-1",
                context -> handler.restore(request, context));

        assertEquals(Set.of(10001L, 10002L), fixture.scenes.scenePlayers("room-1"));
    }

    @Test
    void sourceHookRemovesSceneAndRestoresItAfterRollback() {
        Fixture fixture = new Fixture("scene-1");
        fixture.scenes.enter(10001L, "room-1", 0, 0);
        SmallSceneAgentMigrationStatePacker packer =
                new SmallSceneAgentMigrationStatePacker(fixture.scenes, serializer);
        AgentMigrationSnapshot snapshot = packer.pack(
                AgentIdentity.scene("room-1"),
                new AgentLocation(ServiceId.of(ServiceKind.SCENE, "r1", "scene-2"),
                        new ActorRef("scene:scene-2:room-1")),
                null
        );
        AgentMigrationTask task = new AgentMigrationTask(
                "migration-scene-1",
                AgentIdentity.scene("room-1"),
                new AgentLocation(fixture.serviceId, new ActorRef("scene:scene-1:room-1")),
                new AgentLocation(ServiceId.of(ServiceKind.SCENE, "r1", "scene-2"),
                        new ActorRef("scene:scene-2:room-1")),
                snapshot,
                AgentMigrationTaskStatus.MOVED,
                "",
                Instant.parse("2026-09-01T00:00:00Z")
        );
        SmallSceneAgentMigrationSourceHook hook =
                new SmallSceneAgentMigrationSourceHook(fixture.scenes, serializer);

        hook.sourceMoved(task);

        assertTrue(fixture.scenes.scenePlayers("room-1").isEmpty());

        hook.rollbackRestored(task);

        assertEquals(Set.of(10001L), fixture.scenes.scenePlayers("room-1"));
    }

    private static final class Fixture {
        private final ActorSystem actors = new ActorSystem(Runnable::run, 64);
        private final ServiceId serviceId;
        private final MultiSmallSceneService scenes;

        private Fixture(String node) {
            serviceId = ServiceId.of(ServiceKind.SCENE, "r1", node);
            scenes = new MultiSmallSceneService(actors, serviceId, new ServiceEndpoint("127.0.0.1", 9100), 10);
        }
    }
}
