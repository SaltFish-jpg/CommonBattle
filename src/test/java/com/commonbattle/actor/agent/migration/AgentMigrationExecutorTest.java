package com.commonbattle.actor.agent.migration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMigrationExecutorTest {
    @Test
    void recordsCompletedAndRejectedTasks() throws InterruptedException {
        try (AgentMigrationExecutor executor = new AgentMigrationExecutor("migration-test", 1, 1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                await(release);
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            executor.execute(secondDone::countDown);

            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
            }));
            release.countDown();
            assertTrue(secondDone.await(1, TimeUnit.SECONDS));

            AgentMigrationExecutorStats stats = executor.stats();
            assertEquals(3, stats.submitted());
            assertEquals(1, stats.rejected());
            assertEquals(2, stats.completed());
            assertEquals(0, stats.failed());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
