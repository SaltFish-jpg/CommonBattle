package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InMemoryRpcIdempotencyStoreTest {
    @Test
    void evictsEldestEntryWhenCapacityExceeded() {
        InMemoryRpcIdempotencyStore store = new InMemoryRpcIdempotencyStore(2);
        RpcIdempotencyKey first = key("first");
        RpcIdempotencyKey second = key("second");
        RpcIdempotencyKey third = key("third");

        store.put(first, RpcIdempotencyResult.success("one"));
        store.put(second, RpcIdempotencyResult.success("two"));
        store.put(third, RpcIdempotencyResult.success("three"));

        assertNull(store.get(first));
        assertEquals("two", store.get(second).payload());
        assertEquals("three", store.get(third).payload());
        assertEquals(2, store.size());
    }

    @Test
    void zeroCapacityDisablesStorage() {
        InMemoryRpcIdempotencyStore store = new InMemoryRpcIdempotencyStore(0);

        store.put(key("ignored"), RpcIdempotencyResult.success("value"));

        assertEquals(0, store.size());
    }

    @Test
    void ttlExpiresStoredResult() {
        MutableClock clock = new MutableClock();
        InMemoryRpcIdempotencyStore store = new InMemoryRpcIdempotencyStore(10, Duration.ofSeconds(1), clock);
        RpcIdempotencyKey key = key("expiring");

        store.put(key, RpcIdempotencyResult.success("value"));
        assertEquals("value", store.get(key).payload());

        clock.advance(Duration.ofSeconds(1));

        assertNull(store.get(key));
        assertEquals(0, store.size());
    }

    @Test
    void zeroTtlKeepsStoredResultUntilCapacityEvictsIt() {
        MutableClock clock = new MutableClock();
        InMemoryRpcIdempotencyStore store = new InMemoryRpcIdempotencyStore(10, Duration.ZERO, clock);
        RpcIdempotencyKey key = key("stable");

        store.put(key, RpcIdempotencyResult.success("value"));
        clock.advance(Duration.ofDays(30));

        assertEquals("value", store.get(key).payload());
        assertEquals(1, store.size());
    }

    private static RpcIdempotencyKey key(String value) {
        return new RpcIdempotencyKey(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), "scene.reserve", value);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
