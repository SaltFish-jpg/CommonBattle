package com.commonbattle.game.player;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.player.event.GrowthLevelChangedEvent;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlayerMutationRecoveryEndToEndTest {
    private static final long PLAYER_ID = 10001L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void rebuildReplaysEventLeftBetweenAtomicCommitAndOutboxHandoff() {
        SerializedPlayerMutationRepository repository = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        RecordingExecutor firstExecutor = new RecordingExecutor();
        PlayerGameAgentManager first = manager(firstExecutor, repository, event -> {
            throw new IllegalStateException("outbox unavailable");
        }, "game-1");
        PlayerGameAgent firstAgent = first.getOrCreate(PLAYER_ID);
        firstExecutor.runAll();

        firstAgent.execute(execution -> execution.publish(new GrowthLevelChangedEvent(PLAYER_ID, 1, 2)));
        firstExecutor.runAll();

        assertEquals(1, repository.load(PLAYER_ID).orElseThrow().revision());
        assertEquals(1, repository.pendingEvents(PLAYER_ID).size());

        RecordingEventPublisher recoveredPublisher = new RecordingEventPublisher();
        RecordingExecutor secondExecutor = new RecordingExecutor();
        PlayerGameAgentManager rebuilt = manager(secondExecutor, repository, recoveredPublisher, "game-2");

        rebuilt.getOrCreate(PLAYER_ID);
        secondExecutor.runAll();

        assertEquals(1, recoveredPublisher.events.size());
        assertEquals(1, recoveredPublisher.events.getFirst().revision());
        assertEquals(List.of(), repository.pendingEvents(PLAYER_ID));
    }

    @Test
    void schedulerRetriesPendingEventWithoutProcessRestart() {
        SerializedPlayerMutationRepository repository = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        SwitchableEventPublisher publisher = new SwitchableEventPublisher();
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgentManager manager = manager(executor, repository, publisher, "game-online");
        PlayerGameAgent agent = manager.getOrCreate(PLAYER_ID);
        executor.runAll();
        agent.execute(execution -> execution.publish(new GrowthLevelChangedEvent(PLAYER_ID, 1, 2)));
        executor.runAll();
        assertEquals(1, repository.pendingEvents(PLAYER_ID).size());

        publisher.available = true;
        try (PlayerMutationReplayScheduler scheduler =
                     new PlayerMutationReplayScheduler(manager, Duration.ofSeconds(1))) {
            scheduler.replayOnce();
            executor.runAll();
            assertEquals(1, scheduler.stats().runs());
        }

        assertEquals(1, publisher.events.size());
        assertEquals(List.of(), repository.pendingEvents(PLAYER_ID));
    }

    @Test
    void completedResponseSurvivesCrashImmediatelyAfterStateCommit() {
        SerializedPlayerMutationRepository repository = new SerializedPlayerMutationRepository(
                new InMemoryAtomicBytesStore(),
                new InMemoryPlayerStateRepository(),
                new ProtostuffPlayerStateSnapshotSerializer()
        );
        MutationPlayerBusinessResponseStore responseStore = new MutationPlayerBusinessResponseStore(repository);
        PlayerBusinessResponseHub firstHub = new PlayerBusinessResponseHub(
                PlayerBusinessResultSink.NOOP,
                CLOCK,
                PlayerBusinessResponseHub.DEFAULT_COMPLETED_RESPONSE_WINDOW,
                responseStore
        );
        RecordingExecutor executor = new RecordingExecutor();
        PlayerGameAgentManager manager = manager(executor, repository, null, "game-response");
        manager.attachBusinessResponseSnapshots(firstHub::snapshotForPlayer);
        PlayerGameAgent agent = manager.getOrCreate(PLAYER_ID);
        executor.runAll();
        PlayerCommand command = new PlayerCommand(
                PLAYER_ID,
                "session-1",
                1,
                1,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("battle-win-1", 1)
        );
        AtomicReference<PlayerBusinessResponse> committed = new AtomicReference<>();

        agent.execute(ignored -> committed.set(agent.executeBusiness(
                command,
                new ActivityProgressCommand("battle-win-1", 1)
        )));
        executor.runAll();
        assertNotNull(committed.get());

        PlayerBusinessResponseHub rebuiltHub = new PlayerBusinessResponseHub(
                PlayerBusinessResultSink.NOOP,
                CLOCK,
                PlayerBusinessResponseHub.DEFAULT_COMPLETED_RESPONSE_WINDOW,
                responseStore
        );
        PlayerBusinessResponse recovered = rebuiltHub.cachedResponse(PlayerBusinessResponseQuery.from(command))
                .orElseThrow();
        assertEquals(committed.get(), recovered);
        assertEquals(1, repository.load(PLAYER_ID).orElseThrow().revision());
    }

    private static PlayerGameAgentManager manager(
            RecordingExecutor executor,
            PlayerStateRepository repository,
            EventPublisher publisher,
            String node
    ) {
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                actors,
                new InMemoryAgentDirectory(),
                CLOCK
        );
        InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
        assertEquals(GameConfigPublishStatus.PUBLISHED,
                configs.publish(ExampleGameConfigs.basic(1, CLOCK.instant())).status());
        return new PlayerGameAgentManager(
                actors,
                messages,
                repository,
                configs,
                lifecycles,
                CLOCK,
                Instant.parse("2026-10-01T00:00:00Z"),
                publisher
        );
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        private final List<VersionedEvent> events = new ArrayList<>();

        @Override
        public void publish(VersionedEvent event) {
            events.add(event);
        }
    }

    private static final class SwitchableEventPublisher implements EventPublisher {
        private final List<VersionedEvent> events = new ArrayList<>();
        private boolean available;

        @Override
        public void publish(VersionedEvent event) {
            if (!available) {
                throw new IllegalStateException("outbox unavailable");
            }
            events.add(event);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        private synchronized void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
