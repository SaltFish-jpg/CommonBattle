package com.commonbattle.game.player.event;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.event.ClusterEventCenter;
import com.commonbattle.cluster.event.ClusterEventOperations;
import com.commonbattle.cluster.event.ClusterEventSubscriptionManager;
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerDomainEventSubscriptionTest {
    @Test
    void subscriptionReceivesOnlyInterestedPlayerDomainEvents() {
        Fixture fixture = fixture();
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        List<PlayerDomainEventDelivery> deliveries = new ArrayList<>();
        processor.on(BattleStageClearedEvent.TYPE, deliveries::add);
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());

        PlayerDomainEventSubscription.register(
                manager,
                processor,
                Set.of(10001L),
                (topic, ownerKeys) -> {
                }
        );
        manager.start();
        fixture.gameEvents().publish(event(20002L, 1));
        fixture.gameEvents().publish(event(10001L, 1));

        assertEquals(1, deliveries.size());
        assertEquals(10001L, deliveries.getFirst().event().playerId());
        assertEquals(1, processor.revisionOf(10001L));
        assertEquals(0, processor.revisionOf(20002L));
    }

    private static Fixture fixture() {
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000,
                Set.of(
                        ClusterEventOperations.SUBSCRIBE,
                        ClusterEventOperations.UNSUBSCRIBE,
                        ClusterEventOperations.PUBLISH,
                        ClusterEventOperations.REPLAY
                ));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of("game.resume"));
        ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
        registry.register(center);
        registry.register(game);
        registry.register(scene);

        ClusterDirectory centerDirectory = directory(registry);
        ClusterDirectory gameDirectory = directory(registry);
        ClusterDirectory sceneDirectory = directory(registry);
        ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
        new ClusterEventCenter(center, transport, centerGateway);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        return new Fixture(
                new ClusterVersionedEventBus(game.id(), gameGateway),
                new ClusterVersionedEventBus(scene.id(), sceneGateway)
        );
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }

    private static PlayerDomainVersionedEvent event(long playerId, long revision) {
        return new PlayerDomainVersionedEvent(
                playerId,
                BattleStageClearedEvent.TYPE,
                "forest-1",
                1,
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }

    private record Fixture(
            ClusterVersionedEventBus gameEvents,
            ClusterVersionedEventBus sceneEvents
    ) {
    }
}
