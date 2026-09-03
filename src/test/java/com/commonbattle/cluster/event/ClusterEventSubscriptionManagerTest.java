package com.commonbattle.cluster.event;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.example.cross.SceneOperations;
import com.commonbattle.game.config.GameConfigAutoRecovery;
import com.commonbattle.game.config.GameConfigChangedEvent;
import com.commonbattle.game.config.GameConfigEventReplayRepairer;
import com.commonbattle.game.config.GameConfigSnapshot;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.LocalGameConfigCache;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.InMemoryProfileSnapshotRepository;
import com.commonbattle.game.profile.LocalProfileCache;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import com.commonbattle.game.profile.ProfileSnapshotRepairer;
import com.commonbattle.game.snapshot.EventReplaySnapshotRepairer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterEventSubscriptionManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void startSubscribesAndReplaysFromCursor() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        LocalProfileCache cache = new LocalProfileCache();
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        manager.register(
                ProfileChangedEvent.TOPIC,
                event -> cache.apply((ProfileChangedEvent) event),
                () -> Map.of("profile:10001", cache.revisionOf(10001L))
        );
        manager.start();

        assertEquals("avatar_3", cache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(2, cache.revisionOf(10001L));
        assertEquals(2, manager.stats().replayDelivered());
        assertEquals(0, manager.stats().replayUnavailableOwners());
    }

    @Test
    void recoverResubscribesAndReplaysEventsPublishedWhileOffline() {
        Fixture fixture = fixture(ClusterEventCenter.DEFAULT_HISTORY_LIMIT);
        LocalProfileCache cache = new LocalProfileCache();
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());
        manager.register(
                ProfileChangedEvent.TOPIC,
                event -> cache.apply((ProfileChangedEvent) event),
                () -> Map.of("profile:10001", cache.revisionOf(10001L))
        );
        manager.start();

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        manager.close();
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        fixture.gameEvents().publish(profileEvent(3, "avatar_4"));
        manager.recover();

        assertEquals("avatar_4", cache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(3, cache.revisionOf(10001L));
        assertEquals(2, manager.stats().subscribeAttempts());
        assertEquals(2, manager.stats().replayDelivered());
    }

    @Test
    void reportsReplayWindowLossForSnapshotRepair() {
        Fixture fixture = fixture(1);
        LocalProfileCache cache = new LocalProfileCache();
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());
        InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
        repository.save(profileEvent(2, "avatar_3").snapshot());
        EventReplaySnapshotRepairer repairer = new EventReplaySnapshotRepairer()
                .register(ProfileChangedEvent.TOPIC, new ProfileSnapshotRepairer(repository, cache));

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        manager.register(
                ProfileChangedEvent.TOPIC,
                event -> cache.apply((ProfileChangedEvent) event),
                () -> Map.of("profile:10001", 0L),
                repairer
        );
        manager.start();

        assertEquals(1, manager.stats().replayDelivered());
        assertEquals(1, manager.stats().replayUnavailableOwners());
        assertEquals(1, manager.stats().replayRepairRequests());
        assertEquals(1, manager.stats().replayRepairOwnerCount());
        assertEquals(0, manager.stats().replayRepairFailures());
        assertEquals(1, repairer.lastReport().refreshed());
        assertEquals(2, cache.revisionOf(10001L));
        assertTrue(!cache.isStale(10001L));
    }

    @Test
    void countsRepairFailureWhenNoSnapshotRepairerRegistered() {
        Fixture fixture = fixture(1);
        LocalProfileCache cache = new LocalProfileCache();
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());

        fixture.gameEvents().publish(profileEvent(1, "avatar_2"));
        fixture.gameEvents().publish(profileEvent(2, "avatar_3"));
        manager.register(
                ProfileChangedEvent.TOPIC,
                event -> cache.apply((ProfileChangedEvent) event),
                () -> Map.of("profile:10001", 0L),
                new EventReplaySnapshotRepairer()
        );
        manager.start();

        assertEquals(1, manager.stats().replayRepairRequests());
        assertEquals(1, manager.stats().replayRepairFailures());
        assertTrue(cache.isStale(10001L));
    }

    @Test
    void configReplayWindowLossTriggersSnapshotRecoveryThroughRepairer() {
        Fixture fixture = fixture(1);
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, ExampleGameConfigs.basic(1, CLOCK.instant())));
        AtomicInteger recoveryCalls = new AtomicInteger();
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(callback -> {
            recoveryCalls.incrementAndGet();
            callback.accept(cache.applySnapshot(GameConfigSnapshot.activeOnly(
                    3,
                    ExampleGameConfigs.basic(3, CLOCK.instant())
            )));
        });
        cache.attachRecoveryTrigger(autoRecovery);
        ClusterEventSubscriptionManager manager = new ClusterEventSubscriptionManager(fixture.sceneEvents());

        fixture.gameEvents().publish(GameConfigChangedEvent.activePublished(2, ExampleGameConfigs.basic(2, CLOCK.instant())));
        fixture.gameEvents().publish(GameConfigChangedEvent.activePublished(3, ExampleGameConfigs.basic(3, CLOCK.instant())));
        manager.register(
                GameConfigChangedEvent.TOPIC,
                cache,
                () -> Map.of(GameConfigChangedEvent.OWNER_KEY, cache.appliedEventRevision()),
                new GameConfigEventReplayRepairer(autoRecovery, cache::appliedEventRevision, cache::stale)
        );
        manager.start();

        assertEquals(3, cache.appliedEventRevision());
        assertEquals(3, cache.active().version());
        assertEquals(1, recoveryCalls.get());
        assertEquals(1, manager.stats().replayUnavailableOwners());
        assertEquals(1, manager.stats().replayRepairRequests());
        assertEquals(0, manager.stats().replayRepairFailures());
    }

    private static Fixture fixture(int historyLimit) {
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
        new ClusterEventCenter(center, transport, centerGateway, historyLimit);
        ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport);
        ClusterRpcGateway sceneGateway = new ClusterRpcGateway(scene, sceneDirectory, topology, transport);
        ClusterVersionedEventBus gameEvents = new ClusterVersionedEventBus(game.id(), gameGateway);
        ClusterVersionedEventBus sceneEvents = new ClusterVersionedEventBus(scene.id(), sceneGateway);
        return new Fixture(gameEvents, sceneEvents);
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

    private static ProfileChangedEvent profileEvent(long revision, String avatar) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary(avatar, "frame_1", "costume_1"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }

    private record Fixture(
            ClusterVersionedEventBus gameEvents,
            ClusterVersionedEventBus sceneEvents
    ) {
    }
}
