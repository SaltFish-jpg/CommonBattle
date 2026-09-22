package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinator;
import com.commonbattle.actor.agent.migration.AgentMigrationCoordinatorStats;
import com.commonbattle.actor.agent.migration.AgentMigrationPolicy;
import com.commonbattle.actor.agent.migration.AgentMigrationResult;
import com.commonbattle.actor.agent.migration.AgentMigrationResultStatus;
import com.commonbattle.actor.agent.migration.AgentMigrationTargetEndpoint;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskIdGenerator;
import com.commonbattle.actor.agent.migration.AgentMigrationTaskStoreStats;
import com.commonbattle.actor.agent.migration.InMemoryAgentMigrationTaskStore;
import com.commonbattle.actor.agent.migration.RemoteAgentMigrationClient;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallSceneAgentMigrationEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void migratesSmallSceneAgentFromOneSceneServiceToAnotherThroughClusterRpc() {
        try (Harness harness = new Harness(false)) {
            AgentIdentity scene = AgentIdentity.scene("room-1");
            harness.sourceScenes.enter(10001L, "room-1", 0, 0);
            harness.sourceScenes.enter(10002L, "room-1", 0, 0);
            harness.sourceLifecycles.activate(scene, harness.sourceScenes.actorId("room-1"));
            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();

            boolean submitted = harness.coordinator.migrate(
                    scene,
                    harness.targetLocation("room-1"),
                    new SmallSceneAgentMigrationStatePacker(harness.sourceScenes, harness.serializer),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
            assertEquals(harness.targetScene.id(), harness.agents.locate(scene).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.MIGRATED,
                    harness.sourceLifecycles.record(scene).orElseThrow().state());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.targetLifecycles.record(scene).orElseThrow().state());
            assertTrue(harness.sourceScenes.scenePlayers("room-1").isEmpty());
            assertEquals(Set.of(10001L, 10002L), harness.targetScenes.scenePlayers("room-1"));
            assertEquals(new AgentMigrationTaskStoreStats(1, 1, 0, 0, 1, 0, 0),
                    harness.taskStore.stats(CLOCK.instant()));
            assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 1, 0, 0, 0, 0, 0, 0),
                    harness.coordinator.stats());
        }
    }

    @Test
    void rollsSmallSceneAgentBackWhenTargetRestoreFails() {
        try (Harness harness = new Harness(true)) {
            AgentIdentity scene = AgentIdentity.scene("room-1");
            harness.sourceScenes.enter(10001L, "room-1", 0, 0);
            harness.sourceLifecycles.activate(scene, harness.sourceScenes.actorId("room-1"));
            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();

            boolean submitted = harness.coordinator.migrate(
                    scene,
                    harness.targetLocation("room-1"),
                    new SmallSceneAgentMigrationStatePacker(harness.sourceScenes, harness.serializer),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.TARGET_REJECTED_ROLLED_BACK, result.get().status());
            assertEquals(harness.sourceScene.id(), harness.agents.locate(scene).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.sourceLifecycles.record(scene).orElseThrow().state());
            assertEquals(Set.of(10001L), harness.sourceScenes.scenePlayers("room-1"));
            assertTrue(harness.targetScenes.scenePlayers("room-1").isEmpty());
            assertTrue(harness.targetLifecycles.record(scene).isEmpty());
        }
    }

    private static final class Harness implements AutoCloseable {
        private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry(CLOCK);
        private final LocalClusterTransport transport = new LocalClusterTransport();
        private final ClusterTopology topology = new ClusterTopology().allow(ServiceKind.SCENE, ServiceKind.SCENE);
        private final ServiceDescriptor sourceScene = descriptor("scene-1", 9101);
        private final ServiceDescriptor targetScene = descriptor("scene-2", 9102);
        private final ClusterDirectory sourceDirectory = new ClusterDirectory(registry);
        private final ClusterDirectory targetDirectory = new ClusterDirectory(registry);
        private final ClusterRpcGateway sourceGateway;
        private final ClusterRpcGateway targetGateway;
        private final ActorSystem sourceActors = new ActorSystem(Runnable::run, 64);
        private final ActorSystem targetActors = new ActorSystem(Runnable::run, 64);
        private final InMemoryAgentDirectory agents = new InMemoryAgentDirectory();
        private final InMemoryAgentMigrationTaskStore taskStore = new InMemoryAgentMigrationTaskStore();
        private final AgentLifecycleManager sourceLifecycles =
                new AgentLifecycleManager(sourceScene.id(), sourceActors, agents, CLOCK);
        private final AgentLifecycleManager targetLifecycles =
                new AgentLifecycleManager(targetScene.id(), targetActors, agents, CLOCK);
        private final MultiSmallSceneService sourceScenes =
                new MultiSmallSceneService(sourceActors, sourceScene.id(), sourceScene.endpoint(), 10);
        private final MultiSmallSceneService targetScenes =
                new MultiSmallSceneService(targetActors, targetScene.id(), targetScene.endpoint(), 10);
        private final ProtoSmallSceneAgentSnapshotSerializer serializer =
                new ProtoSmallSceneAgentSnapshotSerializer();
        private final AgentMigrationCoordinator coordinator;

        private Harness(boolean failRestore) {
            registry.register(sourceScene);
            registry.register(targetScene);
            sourceDirectory.watch(ServiceKind.SCENE);
            targetDirectory.watch(ServiceKind.SCENE);
            sourceGateway = new ClusterRpcGateway(sourceScene, sourceDirectory, topology, transport);
            targetGateway = new ClusterRpcGateway(targetScene, targetDirectory, topology, transport);
            new AgentMigrationTargetEndpoint(
                    targetLifecycles,
                    failRestore
                            ? (request, context) -> {
                                throw new IllegalStateException("restore failed");
                            }
                            : new SmallSceneAgentMigrationRestoreHandler(targetScenes, serializer)
            ).bind(targetGateway);
            coordinator = new AgentMigrationCoordinator(
                    sourceLifecycles,
                    agents,
                    new RemoteAgentMigrationClient(sourceGateway),
                    Runnable::run,
                    AgentMigrationPolicy.defaults(),
                    taskStore,
                    AgentMigrationTaskIdGenerator.defaultGenerator(),
                    CLOCK,
                    new SmallSceneAgentMigrationSourceHook(sourceScenes, serializer)
            );
        }

        private AgentLocation targetLocation(String sceneId) {
            return new AgentLocation(targetScene.id(), new ActorRef(targetScenes.actorId(sceneId)));
        }

        @Override
        public void close() {
            sourceGateway.close();
            targetGateway.close();
            sourceActors.close();
            targetActors.close();
            sourceDirectory.close();
            targetDirectory.close();
            transport.close();
        }
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.SCENE, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of("agent.migration.accept"),
                Map.of("scene.mode", SceneHostingMode.MULTI_SMALL_SCENE.name())
        );
    }
}
