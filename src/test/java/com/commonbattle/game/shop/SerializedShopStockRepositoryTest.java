package com.commonbattle.game.shop;

import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedShopStockRepositoryTest {
    @Test
    void reservesStockThroughSharedAtomicStoreAcrossRepositoryInstances() {
        InMemoryAtomicBytesStore store = new InMemoryAtomicBytesStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        SerializedShopStockRepository first = new SerializedShopStockRepository(store, clock, Duration.ofMinutes(1));
        SerializedShopStockRepository rebuilt = new SerializedShopStockRepository(store, clock, Duration.ofMinutes(1));
        first.set("limited_pack", 1);

        assertTrue(first.reserve("order-10001-1", "limited_pack", 1));
        assertTrue(rebuilt.reserve("order-10001-1", "limited_pack", 1));
        assertEquals(0, rebuilt.remaining("limited_pack"));
        rebuilt.release("order-10001-1", "limited_pack", 1);
        rebuilt.release("order-10001-1", "limited_pack", 1);

        assertEquals(1, first.remaining("limited_pack"));
        assertEquals(0, first.reservationCount());
    }

    @Test
    void concurrentReserveOnlySucceedsUpToRemainingStock() throws Exception {
        InMemoryAtomicBytesStore store = new InMemoryAtomicBytesStore();
        SerializedShopStockRepository stocks = new SerializedShopStockRepository(
                store,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(1)
        );
        stocks.set("limited_pack", 5);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                await(start);
                if (stocks.reserve("order-" + index, "limited_pack", 1)) {
                    success.incrementAndGet();
                }
            });
        }
        var executor = Executors.newFixedThreadPool(workers);
        try {
            tasks.forEach(executor::execute);
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(5, success.get());
        assertEquals(0, stocks.remaining("limited_pack"));
        assertEquals(5, stocks.reservationCount());
    }

    @Test
    void expiredReservationsCanBeReapedAfterRepositoryRebuild() {
        InMemoryAtomicBytesStore store = new InMemoryAtomicBytesStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        SerializedShopStockRepository first = new SerializedShopStockRepository(store, clock, Duration.ofSeconds(5));
        first.set("limited_pack", 1);
        assertTrue(first.reserve("order-10001-1", "limited_pack", 1));
        clock.advance(Duration.ofSeconds(5));
        SerializedShopStockRepository rebuilt = new SerializedShopStockRepository(store, clock, Duration.ofSeconds(5));

        assertEquals(1, rebuilt.reapExpiredReservations());

        assertEquals(1, rebuilt.remaining("limited_pack"));
        assertEquals(0, rebuilt.reservationCount());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
