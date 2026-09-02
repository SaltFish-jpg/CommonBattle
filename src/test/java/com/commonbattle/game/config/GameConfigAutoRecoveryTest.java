package com.commonbattle.game.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigAutoRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void repeatedRevisionGapsOnlyKeepOneRecoveryRequestInFlight() {
        LocalGameConfigCache cache = cacheWithVersionOne();
        BlockingRecoveryPort recovery = new BlockingRecoveryPort();
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(recovery);
        cache.attachRecoveryTrigger(autoRecovery);

        cache.apply(GameConfigChangedEvent.activePublished(3, config(3)));
        cache.apply(GameConfigChangedEvent.activePublished(4, config(4)));

        GameConfigAutoRecoveryStats stats = autoRecovery.stats();
        assertEquals(2, stats.requested());
        assertEquals(1, stats.skippedWhileInFlight());
        assertEquals(1, recovery.calls());
        assertTrue(stats.inFlight());
        assertTrue(cache.stale());
        assertEquals(1, cache.active().version());
    }

    @Test
    void successfulAutoRecoveryClearsStaleCache() {
        LocalGameConfigCache cache = cacheWithVersionOne();
        GameConfigSnapshot snapshot = GameConfigSnapshot.activeOnly(3, config(3));
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(
                callback -> callback.accept(cache.applySnapshot(snapshot))
        );
        cache.attachRecoveryTrigger(autoRecovery);

        cache.apply(GameConfigChangedEvent.activePublished(3, config(3)));

        assertFalse(cache.stale());
        assertEquals(3, cache.appliedEventRevision());
        assertEquals(3, cache.active().version());
        assertEquals(1, autoRecovery.stats().succeeded());
        assertFalse(autoRecovery.stats().inFlight());
    }

    @Test
    void failedAutoRecoveryKeepsStaleAndNextGapCanRetry() {
        LocalGameConfigCache cache = cacheWithVersionOne();
        FailingRecoveryPort recovery = new FailingRecoveryPort();
        GameConfigAutoRecovery autoRecovery = new GameConfigAutoRecovery(recovery);
        cache.attachRecoveryTrigger(autoRecovery);

        cache.apply(GameConfigChangedEvent.activePublished(3, config(3)));
        cache.apply(GameConfigChangedEvent.activePublished(4, config(4)));

        GameConfigAutoRecoveryStats stats = autoRecovery.stats();
        assertEquals(2, recovery.calls());
        assertEquals(2, stats.failed());
        assertFalse(stats.inFlight());
        assertTrue(cache.stale());
        assertEquals(1, cache.appliedEventRevision());
    }

    private static LocalGameConfigCache cacheWithVersionOne() {
        LocalGameConfigCache cache = new LocalGameConfigCache(new GameConfigValidator(), CLOCK);
        cache.apply(GameConfigChangedEvent.activePublished(1, config(1)));
        return cache;
    }

    private static GameConfigPackage config(long version) {
        return InMemoryGameConfigRegistryTest.configWithGrowthForConfigCacheTest(version, 60);
    }

    private static final class BlockingRecoveryPort implements GameConfigRecoveryPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void recover(java.util.function.Consumer<GameConfigApplyResult> callback) {
            calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class FailingRecoveryPort implements GameConfigRecoveryPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void recover(java.util.function.Consumer<GameConfigApplyResult> callback) {
            int call = calls.incrementAndGet();
            callback.accept(GameConfigApplyResult.recoveryFailed(call, "center unavailable"));
        }

        int calls() {
            return calls.get();
        }
    }
}
