package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorTimerHandle;
import com.commonbattle.actor.ActorTimerService;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.activity.ActivityCatalog;
import com.commonbattle.game.activity.ActivityDefinition;
import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.activity.ActivityType;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemCatalog;
import com.commonbattle.game.bag.ItemDefinition;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.growth.GrowthService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerGameAgentPersistenceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void saveCapturesFullPlayerStateInsideMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        PlayerGameAgent agent = createAgent(executor, new PlayerProfile(10001L), 0);
        AtomicReference<PlayerStateSnapshot> saved = new AtomicReference<>();
        agent.onLogin("daily-login", ignored -> {
        });
        executor.runNext();
        agent.useExpItems(1, ignored -> {
        });
        executor.runNext();

        agent.save(repository, saved::set);
        executor.runNext();

        PlayerStateSnapshot snapshot = saved.get();
        assertEquals(1, snapshot.revision());
        assertEquals(100, snapshot.bag().itemCounts().get("gold"));
        assertEquals(1, snapshot.bag().itemCounts().get("exp_potion"));
        assertEquals(60, snapshot.growth().exp());
        assertEquals(snapshot, repository.load(10001L).orElseThrow());
    }

    @Test
    void recoveryServiceRestoresSnapshotAndNextSaveContinuesRevision() {
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        PlayerStateSnapshot seed = new PlayerProfile(10001L, Instant.parse("2026-08-01T00:00:00Z"))
                .snapshot(7, CLOCK.instant());
        repository.save(10001L, seed);
        PlayerAgentRecovery recovery = new PlayerAgentRecoveryService(repository, CLOCK).recover(10001L);
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgent agent = createAgent(executor, recovery.profile(), recovery.snapshot().revision());
        AtomicReference<PlayerStateSnapshot> saved = new AtomicReference<>();

        agent.save(repository, saved::set);
        executor.runNext();

        assertFalse(recovery.created());
        assertEquals(8, saved.get().revision());
        assertEquals(10001L, recovery.profile().playerId());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), recovery.profile().createdAt());
    }

    @Test
    void missingSnapshotCreatesNewProfileRecovery() {
        PlayerAgentRecovery recovery = new PlayerAgentRecoveryService(new InMemoryPlayerStateRepository(), CLOCK)
                .recover(10002L);

        assertTrue(recovery.created());
        assertEquals(10002L, recovery.profile().playerId());
        assertEquals(0, recovery.snapshot().revision());
    }

    @Test
    void migrationExportReturnsSnapshotWithoutWritingRepository() {
        RecordingExecutor executor = new RecordingExecutor();
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        PlayerGameAgent agent = createAgent(executor, new PlayerProfile(10001L), 3);
        AtomicReference<PlayerStateSnapshot> exported = new AtomicReference<>();

        agent.exportForMigration(exported::set);
        executor.runNext();

        assertEquals(4, exported.get().revision());
        assertTrue(repository.load(10001L).isEmpty());
    }

    @Test
    void autoSaveTimerPersistsThroughActorMailbox() throws InterruptedException {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
        PlayerGameAgent agent = createAgent(actors, new PlayerProfile(10001L), 0);
        AtomicReference<PlayerStateSnapshot> saved = new AtomicReference<>();

        try (ActorTimerService timers = new ActorTimerService(actors)) {
            ActorTimerHandle handle = agent.scheduleAutoSave(
                    timers,
                    Duration.ofMillis(1),
                    Duration.ofSeconds(5),
                    repository,
                    saved::set
            );

            assertTrue(awaitQueuedTasks(actors, 1));
            assertTrue(repository.load(10001L).isEmpty());
            executor.runNext();
            handle.cancel();

            assertEquals(1, saved.get().revision());
            assertEquals(saved.get(), repository.load(10001L).orElseThrow());
        }
    }

    private static PlayerGameAgent createAgent(Executor executor, PlayerProfile profile, long revision) {
        ActorSystem actors = new ActorSystem(executor, 64);
        return createAgent(actors, profile, revision);
    }

    private static PlayerGameAgent createAgent(ActorSystem actors, PlayerProfile profile, long revision) {
        ActorRef self = actors.actor("player-" + profile.playerId());
        return new PlayerGameAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                self,
                profile,
                activityService(),
                growthService(),
                CLOCK,
                Instant.parse("2026-08-01T00:00:00Z"),
                revision
        );
    }

    private static ActivityService activityService() {
        ItemCatalog items = itemCatalog();
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "daily-login",
                ActivityType.LOGIN,
                1,
                Reward.of(new ItemStack("gold", 100), new ItemStack("exp_potion", 2))
        ));
        return new ActivityService(activities, new BagService(items));
    }

    private static GrowthService growthService() {
        return new GrowthService(new BagService(itemCatalog()), "exp_potion", 60, 100);
    }

    private static ItemCatalog itemCatalog() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gold", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        return items;
    }

    private static boolean awaitQueuedTasks(ActorSystem actors, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (actors.stats().queuedTasks() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return actors.stats().queuedTasks() >= expected;
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        synchronized void runNext() {
            commands.removeFirst().run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
