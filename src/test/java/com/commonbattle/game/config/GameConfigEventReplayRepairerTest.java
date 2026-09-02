package com.commonbattle.game.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameConfigEventReplayRepairerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void replayWindowLossTriggersConfigSnapshotRecovery() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, config(1)));
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(
                callback -> callback.accept(cache.applySnapshot(GameConfigSnapshot.activeOnly(3, config(3))))
        );
        GameConfigEventReplayRepairer repairer =
                new GameConfigEventReplayRepairer(autoRecovery, cache::appliedEventRevision);

        repairer.repair(GameConfigChangedEvent.TOPIC, Set.of(GameConfigChangedEvent.OWNER_KEY));

        assertEquals(3, cache.appliedEventRevision());
        assertEquals(3, cache.active().version());
        assertEquals(1, autoRecovery.stats().requested());
        assertEquals(1, autoRecovery.stats().succeeded());
    }

    @Test
    void replayRepairIgnoresUnrelatedOwnerKeys() {
        AtomicInteger calls = new AtomicInteger();
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(callback -> calls.incrementAndGet());
        GameConfigEventReplayRepairer repairer = new GameConfigEventReplayRepairer(autoRecovery, () -> 0);

        repairer.repair(GameConfigChangedEvent.TOPIC, Set.of("profile:10001"));

        assertEquals(0, calls.get());
        assertEquals(0, autoRecovery.stats().requested());
    }

    @Test
    void skipsRecoveryWhenCacheWasAlreadyRepairedByDeliveredGapEvent() {
        AtomicInteger calls = new AtomicInteger();
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(callback -> calls.incrementAndGet());
        GameConfigEventReplayRepairer repairer = new GameConfigEventReplayRepairer(autoRecovery, () -> 3, () -> false);

        repairer.repair(GameConfigChangedEvent.TOPIC, Set.of(GameConfigChangedEvent.OWNER_KEY));

        assertEquals(0, calls.get());
        assertEquals(0, autoRecovery.stats().requested());
    }

    private static GameConfigPackage config(long version) {
        return InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(version, 60);
    }
}
