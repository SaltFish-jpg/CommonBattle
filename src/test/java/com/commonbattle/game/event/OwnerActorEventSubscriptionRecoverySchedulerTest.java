package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerActorEventSubscriptionRecoverySchedulerTest {
    @Test
    void recoverOnceTriggersEveryRegisteredSubscription() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        OwnerActorEventSubscriptionRecoveryScheduler scheduler = scheduler(List.of(first::incrementAndGet, second::incrementAndGet));

        int recovered = scheduler.recoverOnce();

        assertEquals(2, recovered);
        assertEquals(1, first.get());
        assertEquals(1, second.get());
        assertEquals(new OwnerActorEventSubscriptionRecoveryStats(1, 1, 0, 0, 2, 0, false), scheduler.stats());
    }

    @Test
    void failedSubscriptionDoesNotBlockOtherRecoveries() {
        AtomicInteger recoveredTargets = new AtomicInteger();
        OwnerActorEventSubscriptionRecoveryScheduler scheduler = scheduler(List.of(
                recoveredTargets::incrementAndGet,
                () -> {
                    throw new IllegalStateException("center unavailable");
                },
                recoveredTargets::incrementAndGet
        ));

        int recovered = scheduler.recoverOnce();

        assertEquals(2, recovered);
        assertEquals(2, recoveredTargets.get());
        assertEquals(new OwnerActorEventSubscriptionRecoveryStats(1, 0, 1, 0, 2, 1, false), scheduler.stats());
    }

    @Test
    void concurrentRecoveriesSkipWhilePreviousRunIsInFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OwnerActorEventSubscriptionRecoveryScheduler scheduler = scheduler(List.of(() -> {
            entered.countDown();
            await(release);
        }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(scheduler::recoverOnce);
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            int skipped = scheduler.recoverOnce();
            release.countDown();

            assertEquals(0, skipped);
            assertEquals(1, first.get(1, TimeUnit.SECONDS));
            assertEquals(new OwnerActorEventSubscriptionRecoveryStats(1, 1, 0, 1, 1, 0, false), scheduler.stats());
        } finally {
            executor.shutdownNow();
        }
    }

    private static OwnerActorEventSubscriptionRecoveryScheduler scheduler(
            List<OwnerActorEventRecoveryTarget> targets
    ) {
        return new OwnerActorEventSubscriptionRecoveryScheduler(targets, Duration.ofSeconds(1));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
