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

class RemoteAllianceSnapshotReaderTest {
    @Test
    void readsAllianceSnapshotFromRemoteGameService() {
        Fixture fixture = fixture();
        fixture.repository().save(new AllianceSnapshot(100, 3, Set.of(10001L, 10002L)));

        var result = fixture.reader().find(100);

        assertTrue(result.isPresent());
        assertEquals(3, result.orElseThrow().revision());
        assertEquals(Set.of(10001L, 10002L), result.orElseThrow().members());
    }

    @Test
    void returnsEmptyWhenRemoteGameServiceMissesSnapshot() {
        Fixture fixture = fixture();

        var result = fixture.reader().find(100);

        assertTrue(result.isEmpty());
    }

    private static Fixture fixture() {
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
        return new Fixture(repository, new RemoteAllianceSnapshotReader(sceneGateway, Duration.ofSeconds(1)));
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

    private record Fixture(
            InMemoryAllianceSnapshotRepository repository,
            RemoteAllianceSnapshotReader reader
    ) {
    }
}
