package com.commonbattle.game.profile;

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
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProfileCacheSubscriptionTest {
    @Test
    void subscribesProfileEventsAndRepairsReplayGapThroughRemoteSnapshotRpc() throws Exception {
        try (Fixture fixture = Fixture.create(1)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            sceneCache.apply(profileEvent(1, "avatar_1"));
            fixture.repository().save(profileEvent(3, "avatar_3").snapshot());
            fixture.gameEvents().publish(profileEvent(2, "avatar_2"));
            fixture.gameEvents().publish(profileEvent(3, "avatar_3"));

            ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());
            try (ProfileCacheSubscription ignored = ProfileCacheSubscription.register(
                    manager,
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                manager.start();

                assertEquals(3, sceneCache.revisionOf(10001L));
                assertEquals("avatar_3", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
                assertFalse(sceneCache.isStale(10001L));
                assertEquals(1, manager.stats().replayUnavailableOwners());
                assertEquals(1, manager.stats().replayRepairRequests());
                assertEquals(0, manager.stats().replayRepairFailures());
            } finally {
                manager.close();
            }
        }
    }

    private static ProfileChangedEvent profileEvent(long revision, String avatar) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary(avatar, "frame_1", "costume_1"),
                        AllianceBrief.none(),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
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
            LocalClusterTransport transport,
            ClusterRpcGateway centerGateway,
            ClusterRpcGateway gameGateway,
            ClusterRpcGateway sceneGateway,
            InMemoryProfileSnapshotRepository repository,
            ClusterVersionedEventBus gameEvents,
            ClusterVersionedEventBus sceneEvents
    ) implements AutoCloseable {
        private static Fixture create(int historyLimit) {
            LocalClusterTransport transport = new LocalClusterTransport();
            ClusterTopology topology = ClusterTopology.defaultCrossServer();
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9000, Set.of(
                    ClusterEventOperations.SUBSCRIBE,
                    ClusterEventOperations.UNSUBSCRIBE,
                    ClusterEventOperations.PUBLISH,
                    ClusterEventOperations.REPLAY
            ));
            ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9001, Set.of(
                    "game.resume",
                    ProfileSnapshotOperations.GET
            ));
            ServiceDescriptor scene = descriptor(ServiceKind.SCENE, "scene-1", 9002, Set.of(SceneOperations.ENTER));
            ServiceDescriptor proxy = descriptor(ServiceKind.PROXY, "proxy-1", 9003, Set.of("proxy.forward"));
            registry.register(center);
            registry.register(game);
            registry.register(scene);
            registry.register(proxy);
            new ForwardingProxy(transport).bind(proxy);

            ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, directory(registry), topology, transport);
            new ClusterEventCenter(center, transport, centerGateway, historyLimit);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, directory(registry), topology, transport);
            InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
            new ProfileSnapshotEndpoint(repository).bind(gameGateway);
            ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, directory(registry), topology, transport);
            return new Fixture(
                    transport,
                    centerGateway,
                    gameGateway,
                    sceneGateway,
                    repository,
                    new ClusterVersionedEventBus(game.id(), gameGateway),
                    new ClusterVersionedEventBus(scene.id(), sceneGateway)
            );
        }

        @Override
        public void close() {
            centerGateway.close();
            gameGateway.close();
            sceneGateway.close();
            transport.close();
        }
    }
}
