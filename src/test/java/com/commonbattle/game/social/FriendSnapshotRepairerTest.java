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
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
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

class FriendSnapshotRepairerTest {
    @Test
    void repairEnqueuesSnapshotRefreshToSceneActor() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneFriendAwarenessAgent scene = new SceneFriendAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-friend")
        );
        FriendSnapshotRepairer repairer = new FriendSnapshotRepairer(
                playerId -> Optional.of(new FriendSnapshot(playerId, 3, Set.of(20002L))),
                scene
        );
        scene.enter(10001L);
        executor.runNext();

        SnapshotRepairReport report = repairer.repair(Set.of(FriendOwnerKeyParser.ownerKey(10001L)));

        assertTrue(report.successful());
        assertEquals(1, executor.pending());
        assertTrue(scene.friendsOf(10001L).isEmpty());
        executor.runNext();
        assertTrue(scene.friendsOf(10001L).orElseThrow().contains(20002L));
    }

    @Test
    void repairReadsRemoteGameSnapshotThenRefreshesSceneActorMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneFriendAwarenessAgent scene = new SceneFriendAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-friend")
        );
        try (RemoteFixture remote = RemoteFixture.create()) {
            remote.repository().save(new FriendSnapshot(10001L, 3, Set.of(20002L, 30003L)));
            FriendSnapshotRepairer repairer = new FriendSnapshotRepairer(remote.reader(), scene);
            scene.enter(10001L);
            executor.runNext();
            scene.onFriendChanged(new FriendChangedEvent(10001L, 20002L, FriendRelationAction.ADD, 3));
            executor.runNext();
            assertTrue(scene.friendsOf(10001L).orElseThrow().stale());

            SnapshotRepairReport report = repairer.repair(Set.of(FriendOwnerKeyParser.ownerKey(10001L)));

            assertTrue(report.successful());
            assertEquals(1, executor.pending());
            assertTrue(scene.friendsOf(10001L).orElseThrow().stale());
            executor.runNext();
            assertEquals(Set.of(20002L, 30003L), scene.friendsOf(10001L).orElseThrow().friends());
            assertEquals(3, scene.friendsOf(10001L).orElseThrow().revision());
            assertFalse(scene.friendsOf(10001L).orElseThrow().stale());
        }
    }

    @Test
    void repairReportsInvalidOwnerKey() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        SceneFriendAwarenessAgent scene = new SceneFriendAwarenessAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-friend")
        );
        FriendSnapshotRepairer repairer = new FriendSnapshotRepairer(
                playerId -> Optional.empty(),
                scene
        );

        SnapshotRepairReport report = repairer.repair(Set.of("bad:10001"));

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

    private static ClusterTopology directFriendTopology() {
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
            InMemoryFriendSnapshotRepository repository,
            RemoteFriendSnapshotReader reader
    ) implements AutoCloseable {
        private static RemoteFixture create() {
            LocalClusterTransport transport = new LocalClusterTransport();
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of(FriendSnapshotOperations.GET));
            ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
            registry.register(game);
            registry.register(scene);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                    game,
                    directory(registry),
                    directFriendTopology(),
                    transport
            );
            InMemoryFriendSnapshotRepository repository = new InMemoryFriendSnapshotRepository();
            new FriendSnapshotEndpoint(repository).bind(gameGateway);
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(
                    scene,
                    directory(registry),
                    directFriendTopology(),
                    transport
            );
            return new RemoteFixture(
                    transport,
                    gameGateway,
                    sceneGateway,
                    repository,
                    new RemoteFriendSnapshotReader(sceneGateway, Duration.ofSeconds(1))
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
