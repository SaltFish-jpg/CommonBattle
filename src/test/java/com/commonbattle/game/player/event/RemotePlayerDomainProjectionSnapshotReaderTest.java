package com.commonbattle.game.player.event;

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
import com.commonbattle.game.achievement.PlayerAchievementsSnapshot;
import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.BattleStageProgressSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.player.InMemoryPlayerStateRepository;
import com.commonbattle.game.player.PlayerStateSnapshot;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.task.PlayerTasksSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemotePlayerDomainProjectionSnapshotReaderTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void readsProjectionSnapshotFromRemoteGameService() {
        Fixture fixture = fixture();
        fixture.repository().save(10001L, state(10001L, 7, Map.of(
                "forest-1", 2,
                "cave-1", 1
        )));

        var result = fixture.reader().find(10001L);

        assertTrue(result.isPresent());
        assertEquals(7, result.orElseThrow().eventRevision());
        assertEquals(3, result.orElseThrow().totalStageClears());
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
        ServiceDescriptor game = descriptor(
                ServiceKind.GAME,
                "game-1",
                9001,
                Set.of(PlayerDomainProjectionSnapshotOperations.GET)
        );
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(game);
        registry.register(scene);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(
                game,
                directory(registry),
                directTopology(),
                transport
        );
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        new PlayerDomainProjectionSnapshotEndpoint(
                new PlayerStateDomainProjectionSnapshotReader(repository)
        ).bind(gameGateway);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(
                scene,
                directory(registry),
                directTopology(),
                transport
        );
        return new Fixture(repository, new RemotePlayerDomainProjectionSnapshotReader(sceneGateway, Duration.ofSeconds(1)));
    }

    private static ClusterTopology directTopology() {
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

    private static PlayerStateSnapshot state(long playerId, long eventRevision, Map<String, Integer> clears) {
        Map<String, BattleStageProgressSnapshot> stages = clears.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new BattleStageProgressSnapshot(entry.getKey(), entry.getValue(), 3, NOW, NOW)
                ));
        return new PlayerStateSnapshot(
                playerId,
                NOW,
                new BagSnapshot(Map.of()),
                new PlayerActivitiesSnapshot(Map.of()),
                new GrowthSnapshot(20, 0),
                PlayerShopSnapshot.empty(),
                new PlayerBattleSnapshot(Map.of(), stages),
                PlayerTasksSnapshot.empty(),
                PlayerAchievementsSnapshot.empty(),
                eventRevision,
                1,
                NOW
        );
    }

    private record Fixture(
            InMemoryPlayerStateRepository repository,
            RemotePlayerDomainProjectionSnapshotReader reader
    ) {
    }
}
