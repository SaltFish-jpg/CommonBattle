package com.commonbattle.game.profile;

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
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteProfileSnapshotReaderTest {
    @Test
    void readsProfileSnapshotFromRemoteGameService() {
        Fixture fixture = fixture();
        fixture.repository().save(snapshot(10001L, 3, "avatar_3"));

        var result = fixture.reader().find(10001L);

        assertTrue(result.isPresent());
        assertEquals(3, result.orElseThrow().revision());
        assertEquals("avatar_3", result.orElseThrow().appearance().avatar());
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
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of(ProfileSnapshotOperations.GET));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(game);
        registry.register(scene);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                game,
                directory(registry),
                directProfileTopology(),
                transport
        );
        InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
        new ProfileSnapshotEndpoint(repository).bind(gameGateway);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(
                scene,
                directory(registry),
                directProfileTopology(),
                transport
        );
        return new Fixture(repository, new RemoteProfileSnapshotReader(sceneGateway, Duration.ofSeconds(1)));
    }

    private static ClusterTopology directProfileTopology() {
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

    private static PlayerProfileSnapshot snapshot(long playerId, long revision, String avatar) {
        return new PlayerProfileSnapshot(
                playerId,
                "hero",
                20,
                new AppearanceSummary(avatar, "frame_1", "costume_1"),
                AllianceBrief.none(),
                new FriendBrief(3, 1),
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }

    private record Fixture(
            InMemoryProfileSnapshotRepository repository,
            RemoteProfileSnapshotReader reader
    ) {
    }
}
