package com.commonbattle.actor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorTimerServiceTest {
    @Test
    void timerOnlyEnqueuesTaskIntoActorMailbox() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-1");
        AtomicInteger runs = new AtomicInteger();

        try (ActorTimerService timers = new ActorTimerService(system)) {
            timers.scheduleOnce(player, Duration.ofMillis(1), ignored -> runs.incrementAndGet());

            assertTrue(awaitQueued(executor, 1));
            assertEquals(0, runs.get());
            assertEquals(1, system.stats().queuedTasksByCategory().get(ActorTaskCategory.TIMER));

            executor.runNext();

            assertEquals(1, runs.get());
        }
    }

    @Test
    void cancelledTimerDoesNotEnqueueTask() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-1");

        try (ActorTimerService timers = new ActorTimerService(system)) {
            ActorTimerHandle handle = timers.scheduleOnce(player, Duration.ofMillis(50), ignored -> {
            });

            assertTrue(handle.cancel());
            TimeUnit.MILLISECONDS.sleep(100);

            assertEquals(0, executor.queued());
        }
    }

    @Test
    void timerSharesActorMailboxOrderWithNormalMessages() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-1");
        List<String> result = new ArrayList<>();

        try (ActorTimerService timers = new ActorTimerService(system)) {
            system.send(player, ignored -> result.add("normal"));
            timers.scheduleOnce(player, Duration.ofMillis(1), ignored -> result.add("timer"));

            assertTrue(awaitQueuedTasks(system, 2));
            assertEquals(1, system.stats().queuedTasksByCategory().get(ActorTaskCategory.DEFAULT));
            assertEquals(1, system.stats().queuedTasksByCategory().get(ActorTaskCategory.TIMER));
            executor.runNext();

            assertEquals(List.of("normal", "timer"), result);
        }
    }

    private static boolean awaitQueued(RecordingExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (executor.queued() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return executor.queued() >= expected;
    }

    private static boolean awaitQueuedTasks(ActorSystem system, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (system.stats().queuedTasks() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return system.stats().queuedTasks() >= expected;
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        synchronized int queued() {
            return commands.size();
        }

        synchronized void runNext() {
            commands.removeFirst().run();
        }
    }
}
