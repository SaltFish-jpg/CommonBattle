package com.commonbattle.game.config;

import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigCenterPersistenceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void activeGrayHistoryAndRevisionSurviveCenterReconstruction() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedGameConfigCenterStateStore firstStore = stateStore(bytes);
        InMemoryGameConfigRegistry firstRegistry = registry();
        List<VersionedEvent> firstEvents = new ArrayList<>();
        GameConfigCenterPublisher first = new GameConfigCenterPublisher(
                firstRegistry, firstEvents::add, firstStore, null
        );

        first.publish(GameConfigValidatorTest.validConfig(1));
        first.publish(GameConfigValidatorTest.validConfig(2));
        first.publishGray(GameConfigValidatorTest.validConfig(3), 40);

        SerializedGameConfigCenterStateStore recoveredStore = stateStore(bytes);
        InMemoryGameConfigRegistry recoveredRegistry = registry();
        GameConfigSnapshot recovered = GameConfigCenterRecovery.restore(recoveredRegistry, recoveredStore)
                .orElseThrow();
        List<VersionedEvent> recoveredEvents = new ArrayList<>();
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(
                recoveredRegistry, recoveredEvents::add, recoveredStore, recovered
        );

        assertEquals(3, recovered.eventRevision());
        assertEquals(2, recovered.activeConfig().version());
        assertNotNull(recovered.grayConfig());
        assertEquals(3, recovered.grayConfig().version());
        assertEquals(40, recovered.grayPercent());
        assertEquals(List.of(1L, 2L, 3L), recoveredRegistry.versions());
        assertEquals(3, publisher.eventRevision());

        GameConfigPublishResult rollback = publisher.rollback(1);

        assertEquals(GameConfigPublishStatus.ROLLED_BACK, rollback.status());
        assertEquals(4, publisher.eventRevision());
        assertEquals(1, publisher.snapshot().activeConfig().version());
        assertEquals(1, recoveredEvents.size());
        assertEquals(4, recoveredStore.loadSnapshot().orElseThrow().eventRevision());
    }

    @Test
    void stateStoreRejectsRevisionRegressionAndVersionPayloadConflict() {
        InMemoryAtomicBytesStore bytes = new InMemoryAtomicBytesStore();
        SerializedGameConfigCenterStateStore store = stateStore(bytes);
        GameConfigPackage versionOne = GameConfigValidatorTest.validConfig(1);
        store.saveVersion(versionOne);
        store.saveSnapshot(GameConfigSnapshot.activeOnly(2, versionOne));

        assertThrows(IllegalStateException.class,
                () -> store.saveSnapshot(GameConfigSnapshot.activeOnly(1, versionOne)));
        GameConfigPackage conflicting = InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(1, 999);
        assertThrows(IllegalStateException.class, () -> store.saveVersion(conflicting));
    }

    @Test
    void persistenceFailureStopsPublisherBeforeEventEmission() {
        InMemoryGameConfigRegistry registry = registry();
        List<VersionedEvent> events = new ArrayList<>();
        GameConfigCenterStateStore failing = new GameConfigCenterStateStore() {
            @Override
            public Optional<GameConfigSnapshot> loadSnapshot() {
                return Optional.empty();
            }

            @Override
            public List<GameConfigPackage> loadVersions() {
                return List.of();
            }

            @Override
            public void saveVersion(GameConfigPackage config) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public void saveSnapshot(GameConfigSnapshot snapshot) {
            }
        };
        GameConfigCenterPublisher publisher = new GameConfigCenterPublisher(
                registry, events::add, failing, null
        );

        assertThrows(IllegalStateException.class,
                () -> publisher.publish(GameConfigValidatorTest.validConfig(1)));
        assertTrue(events.isEmpty());
        assertThrows(IllegalStateException.class,
                () -> publisher.publish(GameConfigValidatorTest.validConfig(2)));
    }

    private static InMemoryGameConfigRegistry registry() {
        return new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
    }

    private static SerializedGameConfigCenterStateStore stateStore(InMemoryAtomicBytesStore bytes) {
        PayloadCodecRegistry codecs = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        return new SerializedGameConfigCenterStateStore(bytes, codecs);
    }
}
