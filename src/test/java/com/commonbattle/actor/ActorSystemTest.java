package com.commonbattle.actor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorSystemTest {
    @Test
    void messagesForSameActorRunInSendOrder() throws InterruptedException {
        try (ActorSystem system = new ActorSystem(2, 8)) {
            ActorRef player = system.actor("player-1");
            List<Integer> result = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(3);

            for (int i = 1; i <= 3; i++) {
                int value = i;
                system.send(player, ignored -> {
                    result.add(value);
                    done.countDown();
                });
            }

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), result);
        }
    }

    @Test
    void callbackCanEnqueueMessageBackToSameMailbox() throws InterruptedException {
        try (ActorSystem system = new ActorSystem(2, 1)) {
            ActorRef player = system.actor("player-1");
            List<String> result = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(2);

            system.send(player, context -> {
                result.add("request");
                done.countDown();
                context.send(context.self(), callback -> {
                    result.add("response");
                    done.countDown();
                });
            });

            assertTrue(done.await(1, TimeUnit.SECONDS));
            assertEquals(List.of("request", "response"), result);
        }
    }

    @Test
    void mailboxIsNotScheduledTwiceWhileRunning() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-1");
        AtomicInteger runs = new AtomicInteger();

        system.send(player, ignored -> runs.incrementAndGet());
        system.send(player, ignored -> runs.incrementAndGet());

        assertEquals(1, executor.queued());
        executor.runNext();
        assertEquals(2, runs.get());
    }

    @Test
    void batchLimitReschedulesBusyMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 2);
        ActorRef player = system.actor("player-1");
        AtomicInteger runs = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            system.send(player, ignored -> runs.incrementAndGet());
        }

        executor.runNext();
        assertEquals(2, runs.get());
        assertEquals(1, executor.queued());

        executor.runNext();
        assertEquals(4, runs.get());
        assertEquals(1, executor.queued());

        executor.runNext();
        assertEquals(5, runs.get());
        assertEquals(0, executor.queued());
    }

    @Test
    void failedTaskIsIsolatedAndNextMessageContinues() {
        RecordingExecutor executor = new RecordingExecutor();
        List<ActorFailure> failures = new ArrayList<>();
        ActorSystem system = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 16, ActorOverflowStrategy.REJECT, java.time.Duration.ZERO),
                failures::add,
                DeadLetterSink.ignore()
        );
        ActorRef player = system.actor("player-1");
        AtomicInteger runs = new AtomicInteger();

        system.send(player, ignored -> {
            throw new IllegalStateException("boom");
        });
        system.send(player, ignored -> runs.incrementAndGet());
        executor.runNext();

        assertEquals(1, failures.size());
        assertEquals("player-1", failures.getFirst().actor().id());
        assertEquals(1, runs.get());
        assertEquals(1, system.stats().failedTasks());
        assertEquals(1, system.stats().completedTasks());
    }

    @Test
    void mailboxCapacityRejectsOverflowIntoDeadLetter() {
        RecordingExecutor executor = new RecordingExecutor();
        List<DeadLetter> deadLetters = new ArrayList<>();
        ActorSystem system = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 1, ActorOverflowStrategy.REJECT, java.time.Duration.ZERO),
                ActorFailureHandler.ignore(),
                deadLetters::add
        );
        ActorRef player = system.actor("player-1");

        system.send(player, ignored -> {
        });
        assertFalse(system.trySend(player, ignored -> {
        }));

        assertEquals(1, deadLetters.size());
        assertEquals(DeadLetterReason.MAILBOX_FULL, deadLetters.getFirst().reason());
        assertEquals(1, system.stats().submittedTasks());
        assertEquals(1, system.stats().rejectedTasks());
    }

    @Test
    void closedSystemRejectsNewMessageIntoDeadLetter() {
        RecordingExecutor executor = new RecordingExecutor();
        List<DeadLetter> deadLetters = new ArrayList<>();
        ActorSystem system = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 16, ActorOverflowStrategy.REJECT, java.time.Duration.ZERO),
                ActorFailureHandler.ignore(),
                deadLetters::add
        );
        ActorRef player = system.actor("player-1");

        system.close();

        assertFalse(system.trySend(player, ignored -> {
        }));
        assertThrows(ActorSystemClosedException.class, () -> system.send(player, ignored -> {
        }));
        assertEquals(2, deadLetters.size());
        assertEquals(DeadLetterReason.SYSTEM_CLOSED, deadLetters.getFirst().reason());
    }

    @Test
    void statsTrackQueuedAndRunningMailboxes() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-1");

        system.send(player, ignored -> {
        });

        assertEquals(1, system.stats().submittedTasks());
        assertEquals(1, system.stats().queuedTasks());
        assertEquals(1, system.stats().runningMailboxes());

        executor.runNext();

        assertEquals(0, system.stats().queuedTasks());
        assertEquals(0, system.stats().runningMailboxes());
        assertEquals(1, system.stats().completedTasks());
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        int queued() {
            return commands.size();
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }
}
