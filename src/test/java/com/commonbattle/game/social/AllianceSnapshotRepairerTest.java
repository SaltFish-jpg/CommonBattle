package com.commonbattle.game.social;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.snapshot.SnapshotRepairReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceSnapshotRepairerTest {
    @Test
    void repairEnqueuesSnapshotRefreshToSceneActor() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneAllianceAwarenessAgent scene = new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-alliance")
        );
        AllianceSnapshotRepairer repairer = new AllianceSnapshotRepairer(
                allianceId -> Optional.of(new AllianceSnapshot(allianceId, 3, Set.of(10001L))),
                scene
        );
        scene.enter(10001L);
        executor.runNext();
        scene.watchAlliance(100);
        executor.runNext();

        SnapshotRepairReport report = repairer.repair(Set.of(AllianceOwnerKeyParser.ownerKey(100)));

        assertTrue(report.successful());
        assertEquals(1, executor.pending());
        assertTrue(scene.allianceOf(10001L).isEmpty());
        executor.runNext();
        assertEquals(100, scene.allianceOf(10001L).orElseThrow().allianceId());
        assertFalse(scene.allianceOf(10001L).orElseThrow().stale());
    }

    @Test
    void repairReadsRemoteGameSnapshotThenRefreshesSceneActorMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneAllianceAwarenessAgent scene = new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-alliance")
        );
        try (RemoteFixture remote = RemoteFixture.create()) {
            remote.repository().save(new AllianceSnapshot(100, 3, Set.of(10001L, 20002L)));
            AllianceSnapshotRepairer repairer = new AllianceSnapshotRepairer(remote.reader(), scene);
            scene.enter(10001L);
            executor.runNext();
            scene.watchAlliance(100);
            executor.runNext();
            scene.onAllianceChanged(new AllianceMemberChangedEvent(100, 10001L, AllianceMemberAction.JOIN, 3));
            executor.runNext();
            assertTrue(scene.allianceOf(10001L).orElseThrow().stale());

            SnapshotRepairReport report = repairer.repair(Set.of(AllianceOwnerKeyParser.ownerKey(100)));

            assertTrue(report.successful());
            assertEquals(1, executor.pending());
            assertTrue(scene.allianceOf(10001L).orElseThrow().stale());
            executor.runNext();
            assertEquals(100, scene.allianceOf(10001L).orElseThrow().allianceId());
            assertEquals(3, scene.allianceOf(10001L).orElseThrow().revision());
            assertFalse(scene.allianceOf(10001L).orElseThrow().stale());
        }
    }

    @Test
    void repairReportsInvalidOwnerKey() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneAllianceAwarenessAgent scene = new SceneAllianceAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-alliance")
        );
        AllianceSnapshotRepairer repairer = new AllianceSnapshotRepairer(
                allianceId -> Optional.empty(),
                scene
        );

        SnapshotRepairReport report = repairer.repair(Set.of("bad:100"));

        assertEquals(1, report.invalidOwnerKeys());
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int pending() {
            return commands.size();
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static ClusterTopology directAllianceTopology() {
        return new ClusterTopology()
                .allow(ServiceKind.SCENE, ServiceKind.GAME)
                .allow(ServiceKind.GAME, ServiceKind.SCENE);
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> operations) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                operations,
                Map.of()
        );
    }

    private record RemoteFixture(
            LocalClusterTransport transport,
            ClusterRpcGateway gameGateway,
            ClusterRpcGateway sceneGateway,
            InMemoryAllianceSnapshotRepository repository,
            RemoteAllianceSnapshotReader reader
    ) implements AutoCloseable {
        private static RemoteFixture create() {
            LocalClusterTransport transport = new LocalClusterTransport();
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of(AllianceSnapshotOperations.GET));
            ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
            registry.register(game);
            registry.register(scene);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                    game,
                    directory(registry),
                    directAllianceTopology(),
                    transport
            );
            InMemoryAllianceSnapshotRepository repository = new InMemoryAllianceSnapshotRepository();
            new AllianceSnapshotEndpoint(repository).bind(gameGateway);
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(
                    scene,
                    directory(registry),
                    directAllianceTopology(),
                    transport
            );
            return new RemoteFixture(
                    transport,
                    gameGateway,
                    sceneGateway,
                    repository,
                    new RemoteAllianceSnapshotReader(sceneGateway, Duration.ofSeconds(1))
            );
        }

        @Override
        public void close() {
            gameGateway.close();
            sceneGateway.close();
            transport.close();
        }
    }
}
