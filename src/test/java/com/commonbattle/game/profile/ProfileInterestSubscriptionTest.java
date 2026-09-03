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
import com.commonbattle.cluster.event.ClusterVersionedEventBus;
import com.commonbattle.cluster.network.ForwardingProxy;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.cross.SceneOperations;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileInterestSubscriptionTest {
    @Test
    void watchReceivesOnlyInterestedProfileOwner() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                interests.watch(10001L);

                fixture.gameEvents().publish(profileEvent(20002L, 1, "avatar_other"));
                fixture.gameEvents().publish(profileEvent(10001L, 1, "avatar_1"));

                assertEquals("avatar_1", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
                assertEquals(0, sceneCache.revisionOf(20002L));
                assertEquals(1, interests.stats().watchedOwners());
                assertEquals(1, interests.stats().watchRequests());
            }
        }
    }

    @Test
    void unwatchStopsProfileOwnerDelivery() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                interests.watch(10001L);
                interests.unwatch(10001L);

                fixture.gameEvents().publish(profileEvent(10001L, 1, "avatar_1"));

                assertEquals(0, sceneCache.revisionOf(10001L));
                assertFalse(interests.watching(10001L));
                assertEquals(1, interests.stats().unwatchRequests());
            }
        }
    }

    @Test
    void duplicateWatchUsesReferenceCountingBeforeRemoteUnsubscribe() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                interests.watch(10001L);
                interests.watch(10001L);

                assertEquals(1, interests.stats().watchedOwners());
                assertEquals(2, interests.stats().watchReferences());
                assertEquals(2, interests.stats().watchRequests());
                assertEquals(Set.of(fixture.sceneId()), fixture.center().subscribers(ProfileChangedEvent.TOPIC));

                interests.unwatch(10001L);
                fixture.gameEvents().publish(profileEvent(10001L, 1, "avatar_1"));

                assertTrue(interests.watching(10001L));
                assertEquals(1, interests.stats().watchedOwners());
                assertEquals(1, interests.stats().watchReferences());
                assertEquals(Set.of(fixture.sceneId()), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
                assertEquals("avatar_1", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());

                interests.unwatch(10001L);

                assertFalse(interests.watching(10001L));
                assertEquals(0, interests.stats().watchedOwners());
                assertEquals(0, interests.stats().watchReferences());
                assertEquals(2, interests.stats().unwatchRequests());
                assertEquals(Set.of(), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
            }
        }
    }

    @Test
    void batchWatchAndUnwatchMultipleOwners() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                interests.watchAll(List.of(10001L, 20002L));

                assertEquals(2, interests.stats().watchedOwners());
                assertEquals(2, interests.stats().watchReferences());
                assertEquals(2, interests.stats().watchRequests());
                assertEquals(1, interests.stats().replayAttempts());

                fixture.gameEvents().publish(profileEvent(10001L, 1, "avatar_1"));
                fixture.gameEvents().publish(profileEvent(20002L, 1, "avatar_2"));
                fixture.gameEvents().publish(profileEvent(30003L, 1, "avatar_3"));

                assertEquals("avatar_1", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
                assertEquals("avatar_2", sceneCache.get(20002L).orElseThrow().snapshot().appearance().avatar());
                assertEquals(0, sceneCache.revisionOf(30003L));

                interests.unwatchAll(List.of(10001L, 20002L));

                assertEquals(0, interests.stats().watchedOwners());
                assertEquals(0, interests.stats().watchReferences());
                assertEquals(2, interests.stats().unwatchRequests());
                assertEquals(Set.of(), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
            }
        }
    }

    @Test
    void batchWatchKeepsReferenceCountForDuplicatePlayers() throws Exception {
        try (Fixture fixture = Fixture.create(ClusterEventCenter.DEFAULT_HISTORY_LIMIT)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                interests.watchAll(Arrays.asList(10001L, 10001L));

                assertEquals(1, interests.stats().watchedOwners());
                assertEquals(2, interests.stats().watchReferences());
                assertEquals(2, interests.stats().watchRequests());
                assertEquals(1, interests.stats().replayAttempts());

                interests.unwatch(10001L);

                assertTrue(interests.watching(10001L));
                assertEquals(1, interests.stats().watchReferences());
                assertEquals(Set.of(fixture.sceneId()), fixture.center().subscribers(ProfileChangedEvent.TOPIC));

                interests.unwatch(10001L);

                assertFalse(interests.watching(10001L));
                assertEquals(Set.of(), fixture.center().subscribers(ProfileChangedEvent.TOPIC));
            }
        }
    }

    @Test
    void watchRepairsReplayGapThroughRemoteSnapshotRpc() throws Exception {
        try (Fixture fixture = Fixture.create(1)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            sceneCache.apply(profileEvent(10001L, 1, "avatar_1"));
            fixture.repository().save(profileEvent(10001L, 3, "avatar_3").snapshot());
            fixture.gameEvents().publish(profileEvent(10001L, 2, "avatar_2"));
            fixture.gameEvents().publish(profileEvent(10001L, 3, "avatar_3"));

            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1))
            )) {
                interests.watch(10001L);

                assertEquals(3, sceneCache.revisionOf(10001L));
                assertEquals("avatar_3", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
                assertFalse(sceneCache.isStale(10001L));
                assertEquals(1, interests.stats().repairRequests());
                assertEquals(0, interests.stats().repairFailures());
            }
        }
    }

    @Test
    void replayGapRepairRunsOnProvidedExecutor() throws Exception {
        try (Fixture fixture = Fixture.create(1)) {
            LocalProfileCache sceneCache = new LocalProfileCache();
            RecordingExecutor repairExecutor = new RecordingExecutor();
            sceneCache.apply(profileEvent(10001L, 1, "avatar_1"));
            fixture.repository().save(profileEvent(10001L, 3, "avatar_3").snapshot());
            fixture.gameEvents().publish(profileEvent(10001L, 2, "avatar_2"));
            fixture.gameEvents().publish(profileEvent(10001L, 3, "avatar_3"));

            try (ProfileInterestSubscription interests = new ProfileInterestSubscription(
                    fixture.sceneEvents(),
                    sceneCache,
                    new RemoteProfileSnapshotReader(fixture.sceneGateway(), Duration.ofSeconds(1)),
                    repairExecutor
            )) {
                interests.watch(10001L);

                assertTrue(sceneCache.isStale(10001L));
                assertEquals(1, repairExecutor.pending());
                repairExecutor.runNext();
                assertFalse(sceneCache.isStale(10001L));
                assertEquals("avatar_3", sceneCache.get(10001L).orElseThrow().snapshot().appearance().avatar());
            }
        }
    }

    private static ProfileChangedEvent profileEvent(long playerId, long revision, String avatar) {
        return new ProfileChangedEvent(
                playerId,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        playerId,
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
            ClusterVersionedEventBus sceneEvents,
            ClusterEventCenter center,
            ServiceId sceneId
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
            ClusterEventCenter eventCenter = new ClusterEventCenter(center, transport, centerGateway, historyLimit);
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
                    new ClusterVersionedEventBus(scene.id(), sceneGateway),
                    eventCenter,
                    scene.id()
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
}
