package com.commonbattle.game.config;

import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.game.event.InMemoryVersionedEventBus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGameConfigCacheTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void centerPublisherUpdatesSubscribedLocalCacheAfterAuthorityPublishSucceeds() {
        InMemoryVersionedEventBus events = new InMemoryVersionedEventBus();
        InMemoryGameConfigRegistry center = registry();
        LocalGameConfigCache gameLocal = new LocalGameConfigCache(new GameConfigValidator(), CLOCK, events);
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(center, events);

        GameConfigPublishResult result = publisher.publish(GameConfigValidatorTest.validConfig(1));

        assertEquals(GameConfigPublishStatus.PUBLISHED, result.status());
        assertEquals(1, publisher.eventRevision());
        assertEquals(1, gameLocal.active().version());
        assertEquals(1, gameLocal.appliedEventRevision());
    }

    @Test
    void rejectedAuthorityPublishDoesNotBroadcastConfigEvent() {
        InMemoryVersionedEventBus events = new InMemoryVersionedEventBus();
        InMemoryGameConfigRegistry center = registry();
        LocalGameConfigCache gameLocal = new LocalGameConfigCache(new GameConfigValidator(), CLOCK, events);
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(center, events);
        publisher.publish(GameConfigValidatorTest.validConfig(1));

        GameConfigPublishResult result = publisher.publish(GameConfigValidatorTest.validConfig(1));

        assertEquals(GameConfigPublishStatus.REJECTED, result.status());
        assertEquals(1, publisher.eventRevision());
        assertEquals(1, gameLocal.active().version());
        assertEquals(1, gameLocal.appliedEventRevision());
    }

    @Test
    void localCacheAppliesGrayAndKeepsActiveVersionStable() {
        InMemoryVersionedEventBus events = new InMemoryVersionedEventBus();
        InMemoryGameConfigRegistry center = registry();
        LocalGameConfigCache sceneLocal = new LocalGameConfigCache(new GameConfigValidator(), CLOCK, events);
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(center, events);
        publisher.publish(GameConfigValidatorTest.validConfig(1));

        publisher.publishGray(InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(2, 120), 100);

        assertEquals(1, sceneLocal.active().version());
        assertEquals(2, sceneLocal.resolve(10001L).version());
        assertEquals(2, sceneLocal.appliedEventRevision());
    }

    @Test
    void rollbackEventCarriesFullConfigAndSwitchesFreshLocalCache() {
        InMemoryVersionedEventBus events = new InMemoryVersionedEventBus();
        InMemoryGameConfigRegistry center = registry();
        LocalGameConfigCache chatLocal = new LocalGameConfigCache(new GameConfigValidator(), CLOCK, events);
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(center, events);
        publisher.publish(GameConfigValidatorTest.validConfig(1));
        publisher.publish(InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(2, 120));

        publisher.rollback(1);

        assertEquals(1, chatLocal.active().version());
        assertEquals(3, chatLocal.appliedEventRevision());
        assertTrue(chatLocal.versions().contains(2L));
    }

    @Test
    void localCacheRejectsRevisionGapAndKeepsSnapshotStale() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);

        GameConfigApplyResult result = cache.apply(GameConfigChangedEvent.activePublished(
                2,
                GameConfigValidatorTest.validConfig(1)
        ));

        assertEquals(GameConfigApplyStatus.GAP, result.status());
        assertTrue(cache.stale());
        assertThrows(IllegalStateException.class, cache::active);
    }

    @Test
    void snapshotRecoveryAtomicallyRestoresCacheAfterRevisionGap() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, GameConfigValidatorTest.validConfig(1)));
        cache.apply(GameConfigChangedEvent.activePublished(
                3,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(2, 120)
        ));

        GameConfigApplyResult result = cache.applySnapshot(GameConfigSnapshot.withGray(
                3,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(2, 120),
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(4, 180),
                100
        ));

        assertEquals(GameConfigApplyStatus.RECOVERED, result.status());
        assertEquals(3, cache.appliedEventRevision());
        assertEquals(2, cache.active().version());
        assertEquals(4, cache.resolve(10001L).version());
    }

    @Test
    void oldSnapshotCannotDowngradeRecoveredCache() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.applySnapshot(GameConfigSnapshot.activeOnly(
                3,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(3, 180)
        ));

        GameConfigApplyResult result = cache.applySnapshot(GameConfigSnapshot.activeOnly(
                2,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(2, 120)
        ));

        assertEquals(GameConfigApplyStatus.DUPLICATE_OR_OLD, result.status());
        assertEquals(3, cache.appliedEventRevision());
        assertEquals(3, cache.active().version());
    }

    @Test
    void staleCacheRejectsSnapshotOlderThanMissingGap() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, GameConfigValidatorTest.validConfig(1)));
        cache.apply(GameConfigChangedEvent.activePublished(
                4,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(4, 240)
        ));

        GameConfigApplyResult result = cache.applySnapshot(GameConfigSnapshot.activeOnly(
                3,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(3, 180)
        ));

        assertEquals(GameConfigApplyStatus.RECOVERY_FAILED, result.status());
        assertTrue(cache.stale());
        assertEquals(1, cache.appliedEventRevision());
        assertEquals(1, cache.active().version());
    }

    @Test
    void remoteRecoveryFetchesSnapshotFromCenterEndpointThroughRpc() {
        InMemoryServiceRegistry services = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = ClusterTopology.defaultCrossServer();
        ServiceDescriptor center = descriptor(ServiceKind.CENTER, "center-1", 9100, Set.of(GameConfigOperations.SNAPSHOT));
        ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9101, Set.of());
        services.register(center);
        services.register(game);
        ClusterDirectory centerDirectory = new ClusterDirectory(services);
        ClusterDirectory gameDirectory = new ClusterDirectory(services);
        gameDirectory.watch(ServiceKind.CENTER);
        InMemoryGameConfigRegistry centerRegistry = registry();
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(
                centerRegistry,
                new InMemoryVersionedEventBus()
        );
        publisher.publish(GameConfigValidatorTest.validConfig(1));
        publisher.publishGray(InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(2, 120), 100);
        publisher.publishGray(InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(3, 180), 100);
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, GameConfigValidatorTest.validConfig(1)));
        cache.apply(GameConfigChangedEvent.activePublished(
                3,
                InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(3, 180)
        ));
        AtomicReference<GameConfigApplyResult> recovered = new AtomicReference<>();

        try (ClusterRpcGateway centerGateway = new ClusterRpcGateway(center, centerDirectory, topology, transport);
             ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, gameDirectory, topology, transport)) {
            new GameConfigCenterEndpoint(publisher).bind(centerGateway);
            new RemoteGameConfigRecoveryClient(gameGateway, cache).recover(recovered::set);
        }

        assertEquals(GameConfigApplyStatus.RECOVERED, recovered.get().status());
        assertEquals(3, cache.appliedEventRevision());
        assertEquals(1, cache.active().version());
        assertEquals(3, cache.resolve(10001L).version());
        assertEquals(2, cache.versions().size());
    }

    @Test
    void localCacheIgnoresDuplicateEvent() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        GameConfigChangedEvent event = GameConfigChangedEvent.activePublished(1, GameConfigValidatorTest.validConfig(1));
        cache.apply(event);

        GameConfigApplyResult result = cache.apply(event);

        assertEquals(GameConfigApplyStatus.DUPLICATE_OR_OLD, result.status());
        assertEquals(1, cache.active().version());
        assertEquals(1, cache.appliedEventRevision());
    }

    private static InMemoryGameConfigRegistry registry() {
        return new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> topics) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                topics,
                Map.of()
        );
    }
}
