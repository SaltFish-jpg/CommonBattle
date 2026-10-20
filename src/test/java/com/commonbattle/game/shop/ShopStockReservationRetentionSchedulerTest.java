package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTaskCategory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopStockReservationRetentionSchedulerTest {
    @Test
    void timerOnlyEnqueuesAndReapingRunsInsideMaintenanceActor() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 1, 16);
        ActorRef maintenance = actors.actor("center-shop-stock-retention");
        AtomicInteger repositoryRuns = new AtomicInteger();
        ShopStockReservationRepository repository = new StubRepository(repositoryRuns);
        ShopStockReservationRetentionService retention = new ShopStockReservationRetentionService(repository);
        ShopStockReservationRetentionScheduler scheduler = new ShopStockReservationRetentionScheduler(
                retention,
                Duration.ofSeconds(1),
                actors,
                maintenance
        );

        assertTrue(scheduler.enqueueOnce());

        assertEquals(0, repositoryRuns.get());
        assertEquals(1, actors.stats().queuedTasksByCategory().get(ActorTaskCategory.TIMER));
        executor.runNext();
        assertEquals(1, repositoryRuns.get());
        assertEquals(1, retention.stats().reservationReapRuns());
        assertEquals(3, retention.stats().reapedReservations());
        scheduler.close();
        actors.close();
    }

    private static final class StubRepository implements ShopStockReservationRepository {
        private final AtomicInteger runs;

        private StubRepository(AtomicInteger runs) {
            this.runs = runs;
        }

        @Override
        public boolean reserve(String sku, int count) {
            return false;
        }

        @Override
        public void release(String sku, int count) {
        }

        @Override
        public int remaining(String sku) {
            return 0;
        }

        @Override
        public int reservationCount() {
            return 0;
        }

        @Override
        public int reapExpiredReservations() {
            runs.incrementAndGet();
            return 3;
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}
