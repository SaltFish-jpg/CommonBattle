package com.commonbattle.game.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPlayerSessionRegistryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rebindIncrementsEpochAndInvalidatesOldSession() {
        InMemoryPlayerSessionRegistry registry = new InMemoryPlayerSessionRegistry(CLOCK);

        PlayerSession first = registry.bind(10001L, "session-1");
        PlayerSession second = registry.bind(10001L, "session-2");

        assertFalse(registry.isCurrent(10001L, first.sessionId(), first.epoch()));
        assertTrue(registry.isCurrent(10001L, second.sessionId(), second.epoch()));
    }

    @Test
    void unbindOnlyRemovesCurrentMatchingSession() {
        InMemoryPlayerSessionRegistry registry = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerSession first = registry.bind(10001L, "session-1");
        PlayerSession second = registry.bind(10001L, "session-2");

        registry.unbind(first);
        assertTrue(registry.isCurrent(10001L, second.sessionId(), second.epoch()));

        registry.unbind(second);
        assertTrue(registry.current(10001L).isEmpty());
    }
}
