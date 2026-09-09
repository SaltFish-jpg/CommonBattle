package com.commonbattle.cluster.registry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteRegistryRecoverySchedulerTest {
    @Test
    void recoverOnceRecordsSuccessfulRecoveredKinds() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try (RemoteRegistryRecoveryScheduler scheduler = new RemoteRegistryRecoveryScheduler(
                () -> 3,
                Duration.ofSeconds(1),
                executor
        )) {
            assertEquals(3, scheduler.recoverOnce());

            RemoteRegistryRecoveryStats stats = scheduler.stats();
            assertEquals(1, stats.runs());
            assertEquals(1, stats.succeededRuns());
            assertEquals(0, stats.failedRuns());
            assertEquals(3, stats.recoveredKinds());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoverOnceRecordsFailuresAndContinuesForSafeRuns() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger attempts = new AtomicInteger();
        try (RemoteRegistryRecoveryScheduler scheduler = new RemoteRegistryRecoveryScheduler(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("center unavailable");
                    }
                    return 1;
                },
                Duration.ofSeconds(1),
                executor
        )) {
            assertThrows(IllegalStateException.class, scheduler::recoverOnce);
            scheduler.recoverSafely();

            RemoteRegistryRecoveryStats stats = scheduler.stats();
            assertEquals(2, stats.runs());
            assertEquals(1, stats.succeededRuns());
            assertEquals(1, stats.failedRuns());
            assertEquals(1, stats.recoveredKinds());
        } finally {
            executor.shutdownNow();
        }
    }
}
