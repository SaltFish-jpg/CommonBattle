package com.commonbattle.game.social;

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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteFriendSnapshotReaderTest {
    @Test
    void readsFriendSnapshotFromRemoteGameService() {
        Fixture fixture = fixture();
        fixture.repository().save(new FriendSnapshot(10001L, 3, Set.of(20002L, 30003L)));

        var result = fixture.reader().find(10001L);

        assertTrue(result.isPresent());
        assertEquals(3, result.orElseThrow().revision());
        assertEquals(Set.of(20002L, 30003L), result.orElseThrow().friends());
    }

    @Test
    void returnsEmptyWhenRemoteGameServiceMissesSnapshot() {
        Fixture fixture = fixture();

        var result = fixture.reader().find(10001L);

        assertTrue(result.isEmpty());
    }

    private static Fixture fixture() {
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
        return new Fixture(repository, new RemoteFriendSnapshotReader(sceneGateway, Duration.ofSeconds(1)));
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

    private record Fixture(
            InMemoryFriendSnapshotRepository repository,
            RemoteFriendSnapshotReader reader
    ) {
    }
}
