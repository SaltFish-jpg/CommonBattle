package com.commonbattle.game.shop;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryShopStockRepositoryTest {
    @Test
    void reservationIdMakesReserveAndReleaseIdempotent() {
        InMemoryShopStockRepository stocks = new InMemoryShopStockRepository();
        stocks.set("limited_pack", 1);

        assertTrue(stocks.reserve("order-10001-1", "limited_pack", 1));
        assertTrue(stocks.reserve("order-10001-1", "limited_pack", 1));
        assertFalse(stocks.reserve("order-10001-2", "limited_pack", 1));
        assertEquals(0, stocks.remaining("limited_pack"));
        assertEquals(1, stocks.reservationCount());

        stocks.release("order-10001-1", "limited_pack", 1);
        stocks.release("order-10001-1", "limited_pack", 1);

        assertEquals(1, stocks.remaining("limited_pack"));
        assertEquals(0, stocks.reservationCount());
    }

    @Test
    void expiredReservationsCanBeReapedBackToStock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        InMemoryShopStockRepository stocks = new InMemoryShopStockRepository(clock, Duration.ofSeconds(5));
        stocks.set("limited_pack", 1);
        assertTrue(stocks.reserve("order-10001-1", "limited_pack", 1));

        clock.advance(Duration.ofSeconds(5));

        assertEquals(1, stocks.reapExpiredReservations());
        assertEquals(1, stocks.remaining("limited_pack"));
        assertEquals(0, stocks.reservationCount());
    }

    @Test
    void retentionServiceReportsReapStatsAsShopRuntime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        InMemoryShopStockRepository stocks = new InMemoryShopStockRepository(clock, Duration.ofSeconds(5));
        ShopStockReservationRetentionService retention = new ShopStockReservationRetentionService(stocks);
        stocks.set("limited_pack", 2);
        assertTrue(stocks.reserve("order-10001-1", "limited_pack", 1));
        assertTrue(stocks.reserve("order-10001-2", "limited_pack", 1));
        clock.advance(Duration.ofSeconds(5));

        assertEquals(2, retention.reap());

        assertEquals(2, stocks.remaining("limited_pack"));
        assertEquals(new ShopRuntimeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 1, 2, 0), retention.stats());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
