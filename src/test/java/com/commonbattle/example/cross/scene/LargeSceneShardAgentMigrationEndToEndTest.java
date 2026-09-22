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

class LargeSceneShardAgentMigrationEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void migratesLargeSceneShardAgentBetweenSceneServices() {
        try (Harness harness = new Harness(false)) {
            AgentIdentity shard = shardIdentity(0);
            harness.sourceScenes.enter(10001L, "world-1", 0, 0);
            harness.sourceScenes.enter(10002L, "world-1", 0, 0);
            harness.sourceScenes.enter(10003L, "world-1", 1, 0);
            harness.sourceLifecycles.activate(shard, harness.sourceScenes.actorId(0));
            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();

            boolean submitted = harness.coordinator.migrate(
                    shard,
                    harness.targetLocation(0),
                    new LargeSceneShardAgentMigrationStatePacker(harness.sourceScenes, harness.serializer),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.TARGET_ACCEPTED, result.get().status());
            assertEquals(harness.targetScene.id(), harness.agents.locate(shard).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.MIGRATED,
                    harness.sourceLifecycles.record(shard).orElseThrow().state());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.targetLifecycles.record(shard).orElseThrow().state());
            assertTrue(harness.sourceScenes.shardPlayers(0).isEmpty());
            assertEquals(Set.of(10003L), harness.sourceScenes.shardPlayers(3));
            assertEquals(Set.of(10001L, 10002L), harness.targetScenes.shardPlayers(0));
            assertEquals(new AgentMigrationCoordinatorStats(1, 1, 0, 1, 0, 0, 0, 0, 0, 0),
                    harness.coordinator.stats());
        }
    }

    @Test
    void rollsLargeSceneShardBackWhenTargetRestoreFails() {
        try (Harness harness = new Harness(true)) {
            AgentIdentity shard = shardIdentity(0);
            harness.sourceScenes.enter(10001L, "world-1", 0, 0);
            harness.sourceLifecycles.activate(shard, harness.sourceScenes.actorId(0));
            AtomicReference<AgentMigrationResult> result = new AtomicReference<>();

            boolean submitted = harness.coordinator.migrate(
                    shard,
                    harness.targetLocation(0),
                    new LargeSceneShardAgentMigrationStatePacker(harness.sourceScenes, harness.serializer),
                    result::set
            );

            assertTrue(submitted);
            assertEquals(AgentMigrationResultStatus.TARGET_REJECTED_ROLLED_BACK, result.get().status());
            assertEquals(harness.sourceScene.id(), harness.agents.locate(shard).orElseThrow().serviceId());
            assertEquals(AgentLifecycleState.ACTIVE,
                    harness.sourceLifecycles.record(shard).orElseThrow().state());
            assertEquals(Set.of(10001L), harness.sourceScenes.shardPlayers(0));
            assertTrue(harness.targetScenes.shardPlayers(0).isEmpty());
            assertTrue(harness.targetLifecycles.record(shard).isEmpty());
        }
    }

    private static AgentIdentity shardIdentity(int shardIndex) {
        return AgentIdentity.scene(new LargeSceneShardAgentKey("world-1", shardIndex).wireName());
    }

    private static final class Harness implements AutoCloseable {
        private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry(CLOCK);
        private final LocalClusterTransport transport = new LocalClusterTransport();
        private final ClusterTopology topology = new ClusterTopology().allow(ServiceKind.SCENE, ServiceKind.SCENE);
        private final ServiceDescriptor sourceScene = descriptor("scene-1", 9201);
        private final ServiceDescriptor targetScene = descriptor("scene-2", 9202);
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
        private final LargeSceneShardService sourceScenes =
                new LargeSceneShardService(sourceActors, sourceScene.id(), sourceScene.endpoint(), "world-1", 4);
        private final LargeSceneShardService targetScenes =
                new LargeSceneShardService(targetActors, targetScene.id(), targetScene.endpoint(), "world-1", 4);
        private final ProtoLargeSceneShardAgentSnapshotSerializer serializer =
                new ProtoLargeSceneShardAgentSnapshotSerializer();
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
                            : new LargeSceneShardAgentMigrationRestoreHandler(targetScenes, serializer)
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
                    new LargeSceneShardAgentMigrationSourceHook(sourceScenes, serializer)
            );
        }

        private AgentLocation targetLocation(int shardIndex) {
            return new AgentLocation(targetScene.id(), new ActorRef(targetScenes.actorId(shardIndex)));
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
                Map.of(
                        "scene.mode", SceneHostingMode.LARGE_SCENE_SHARD.name(),
                        "scene.id", "world-1",
                        "scene.shards", "4"
                )
        );
    }
}
