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
        assertEquals(0, system.stats().droppedTasks());
    }

    @Test
    void categoryCapacityOnlyRejectsThatTaskType() {
        RecordingExecutor executor = new RecordingExecutor();
        List<DeadLetter> deadLetters = new ArrayList<>();
        ActorSystem system = new ActorSystem(
                executor,
                ActorSystemConfig.defaults(1)
                        .withMailboxCapacity(4)
                        .withCategoryCapacity(ActorTaskCategory.PLAYER_COMMAND, 1),
                ActorFailureHandler.ignore(),
                deadLetters::add
        );
        ActorRef player = system.actor("player-1");

        system.send(player, ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
        }));
        assertFalse(system.trySend(player, ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
        })));
        assertTrue(system.trySend(player, ignored -> {
        }));

        ActorSystemStats stats = system.stats();
        assertEquals(1, deadLetters.size());
        assertEquals(DeadLetterReason.MAILBOX_CATEGORY_FULL, deadLetters.getFirst().reason());
        assertEquals(2, stats.submittedTasks());
        assertEquals(1, stats.rejectedTasks());
        assertEquals(1, stats.rejectedTasksByCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        assertEquals(1, stats.queuedTasksByCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        assertEquals(1, stats.queuedTasksByCategory().get(ActorTaskCategory.DEFAULT));
    }

    @Test
    void dropOldestOverflowStrategyKeepsNewestMessages() {
        RecordingExecutor executor = new RecordingExecutor();
        List<DeadLetter> deadLetters = new ArrayList<>();
        ActorSystem system = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 2, ActorOverflowStrategy.DROP_OLDEST, java.time.Duration.ZERO),
                ActorFailureHandler.ignore(),
                deadLetters::add
        );
        ActorRef player = system.actor("player-1");
        List<Integer> runs = new ArrayList<>();

        system.send(player, ignored -> runs.add(1));
        system.send(player, ignored -> runs.add(2));
        assertTrue(system.trySend(player, ignored -> runs.add(3)));

        assertEquals(1, deadLetters.size());
        assertEquals(DeadLetterReason.DROPPED_BY_OVERFLOW, deadLetters.getFirst().reason());
        assertEquals(3, system.stats().submittedTasks());
        assertEquals(1, system.stats().droppedTasks());
        assertEquals(2, system.stats().queuedTasks());
        assertEquals(1, system.stats().droppedTasksByCategory().get(ActorTaskCategory.DEFAULT));

        executor.runNext();

        assertEquals(List.of(2, 3), runs);
        assertEquals(0, system.stats().queuedTasks());
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

    @Test
    void statsExposeLargestMailboxAndPeaks() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-1");
        ActorRef scene = system.actor("scene-1");

        system.send(player, ignored -> {
        });
        system.send(player, ignored -> {
        });
        system.send(scene, ignored -> {
        });

        ActorSystemStats queued = system.stats();
        assertEquals(3, queued.queuedTasks());
        assertEquals(2, queued.activeMailboxes());
        assertEquals(2, queued.largestMailboxQueuedTasks());
        assertEquals("player-1", queued.largestMailboxActorId());
        assertEquals(3, queued.peakQueuedTasks());
        assertEquals(2, queued.peakRunningMailboxes());
        assertEquals(3, queued.queuedTasksByCategory().get(ActorTaskCategory.DEFAULT));

        executor.runNext();
        executor.runNext();

        ActorSystemStats drained = system.stats();
        assertEquals(0, drained.queuedTasks());
        assertEquals(0, drained.activeMailboxes());
        assertEquals(0, drained.largestMailboxQueuedTasks());
        assertEquals("", drained.largestMailboxActorId());
        assertEquals(3, drained.peakQueuedTasks());
        assertEquals(2, drained.peakRunningMailboxes());
        assertEquals(0, drained.queuedTasksByCategory().get(ActorTaskCategory.DEFAULT));
    }

    @Test
    void queuedMailboxStatsExposeHotMailboxesInStableOrder() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(executor, 64);
        ActorRef player = system.actor("player-10001");
        ActorRef scene = system.actor("scene-shard:world-1:0");

        system.send(scene, ActorTask.categorized(ActorTaskCategory.TIMER, ignored -> {
        }));
        system.send(player, ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
        }));
        system.send(player, ActorTask.categorized(ActorTaskCategory.RPC_CALLBACK, ignored -> {
        }));

        List<ActorMailboxStats> stats = system.queuedMailboxStats();

        assertEquals(2, stats.size());
        assertEquals("player-10001", stats.get(0).actor().id());
        assertEquals(2, stats.get(0).queuedTasks());
        assertEquals(1, stats.get(0).queuedTasksByCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        assertEquals(1, stats.get(0).queuedTasksByCategory().get(ActorTaskCategory.RPC_CALLBACK));
        assertEquals("scene-shard:world-1:0", stats.get(1).actor().id());
        assertEquals(1, stats.get(1).queuedTasksByCategory().get(ActorTaskCategory.TIMER));
    }

    @Test
    void statsTrackSlowestTaskAndSlowTaskCount() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem system = new ActorSystem(
                executor,
                ActorSystemConfig.defaults(1).withSlowTaskThreshold(java.time.Duration.ofMillis(1)),
                ActorFailureHandler.ignore(),
                DeadLetterSink.ignore()
        );
        ActorRef player = system.actor("player-1");

        system.send(player, ActorTask.categorized(ActorTaskCategory.PLAYER_COMMAND, ignored -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        executor.runNext();

        ActorSystemStats stats = system.stats();
        assertEquals(1, stats.completedTasks());
        assertEquals(1, stats.slowTasks());
        assertTrue(stats.slowestTaskMillis() >= 1);
        assertEquals("player-1", stats.slowestTaskActorId());
        assertEquals(ActorTaskCategory.PLAYER_COMMAND, stats.slowestTaskCategory());
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
