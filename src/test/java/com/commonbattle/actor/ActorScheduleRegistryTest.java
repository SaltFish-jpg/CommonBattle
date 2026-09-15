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

class ActorScheduleRegistryTest {
    @Test
    void scheduleOnceOnlyEnqueuesTimerMessageIntoActorMailbox() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-10001");
        AtomicInteger runs = new AtomicInteger();

        try (ActorScheduleRegistry schedules = new ActorScheduleRegistry(system)) {
            schedules.scheduleOnce(
                    ActorScheduleKey.of("player.daily.reset", 10001L),
                    player,
                    Duration.ofMillis(1),
                    ignored -> runs.incrementAndGet()
            );

            assertTrue(awaitQueued(executor, 1));
            assertEquals(1, schedules.stats().deliveredTimerMessages());
            assertEquals(0, schedules.stats().activeJobs());
            assertEquals(0, runs.get());
            assertEquals(1, system.stats().queuedTasksByCategory().get(ActorTaskCategory.TIMER));

            executor.runNext();

            assertEquals(1, runs.get());
        }
    }

    @Test
    void replacingSameBusinessKeyCancelsPreviousSchedule() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef scene = system.actor("scene-1");
        ActorScheduleKey key = new ActorScheduleKey("scene.tick", "scene-1");
        List<String> runs = new ArrayList<>();

        try (ActorScheduleRegistry schedules = new ActorScheduleRegistry(system)) {
            schedules.scheduleOnce(key, scene, Duration.ofMillis(100), ignored -> runs.add("old"));
            schedules.scheduleOnce(key, scene, Duration.ofMillis(1), ignored -> runs.add("new"));

            assertTrue(awaitQueued(executor, 1));
            executor.runNext();

            assertEquals(List.of("new"), runs);
            assertEquals(2, schedules.stats().scheduledJobs());
            assertEquals(1, schedules.stats().cancelledJobs());
            assertEquals(0, schedules.stats().activeJobs());
        }
    }

    @Test
    void cancelByBusinessKeyPreventsTimerDelivery() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-10001");
        ActorScheduleKey key = ActorScheduleKey.of("player.auto.save", 10001L);

        try (ActorScheduleRegistry schedules = new ActorScheduleRegistry(system)) {
            schedules.scheduleOnce(key, player, Duration.ofMillis(50), ignored -> {
            });

            assertTrue(schedules.cancel(key));
            TimeUnit.MILLISECONDS.sleep(100);

            assertEquals(0, executor.queued());
            assertEquals(0, schedules.stats().activeJobs());
            assertEquals(1, schedules.stats().cancelledJobs());
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
