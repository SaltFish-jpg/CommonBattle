package com.commonbattle.game.player;

import com.commonbattle.actor.ActorScheduleRegistry;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleState;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.event.EventPublisher;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.ReliableVersionedEventPublisher;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandAuditOutcome;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerLoginDrainingException;
import com.commonbattle.game.session.PlayerLoginResult;
import com.commonbattle.game.session.PlayerLoginService;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.observability.RuntimeHealthJsonFormatter;
import com.commonbattle.observability.RuntimeHealthPolicy;
import com.commonbattle.observability.RuntimeHealthProbe;
import com.commonbattle.observability.RuntimeHealthRegistry;
import com.commonbattle.observability.RuntimeHealthSnapshot;
import com.commonbattle.observability.RuntimeHealthStatus;
import com.commonbattle.observability.RuntimeMetricsFormatter;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerGameAgentManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void createsRestoresAndReusesPlayerAgent() {
        Fixture fixture = Fixture.create();
        PlayerStateSnapshot seed = new PlayerProfile(10001L, SERVER_OPEN_TIME)
                .snapshot(7, 5, CLOCK.instant());
        fixture.repository.save(10001L, seed);

        PlayerGameAgent first = fixture.manager.getOrCreate(10001L);
        PlayerGameAgent second = fixture.manager.getOrCreate(10001L);
        fixture.executor.runAll();

        assertSame(first, second);
        assertEquals(1, fixture.manager.loadedAgents());
        assertEquals(SERVER_OPEN_TIME, first.profile().createdAt());
        assertEquals("player-10001", fixture.directory.locate(AgentIdentity.player(10001L))
                .orElseThrow().actorRef().id());
    }

    @Test
    void recoveredEventRevisionContinuesWhenDomainEventIsPublished() {
        RecordingEventPublisher published = new RecordingEventPublisher();
        Fixture fixture = Fixture.create(new ReliableVersionedEventPublisher(
                new InMemoryVersionedEventOutbox(CLOCK),
                published
        ));
        fixture.repository.save(10001L, new PlayerProfile(10001L, SERVER_OPEN_TIME)
                .snapshot(3, 5, CLOCK.instant()));
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);

        agent.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        fixture.executor.runAll();

        PlayerDomainVersionedEvent event = assertInstanceOf(PlayerDomainVersionedEvent.class,
                published.events.getFirst());
        assertEquals(10001L, event.playerId());
        assertEquals("forest-1", event.subject());
        assertEquals(6, event.revision());
    }

    @Test
    void businessCommandHandlerCanUseManagerLoadedAgent() {
        Fixture fixture = Fixture.create();
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);
        fixture.executor.runAll();
        RecordingResultSink results = new RecordingResultSink();
        PlayerBusinessCommandHandler handler = new PlayerBusinessCommandHandler(fixture.manager::getOrCreate, results);
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        sessions.bind(10001L, "session-1");
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                new PlayerCommandSequencer(),
                new AdmissionControlledAgentRouter(
                        (target, operation) -> AdmissionDecision.accept(),
                        new LifecycleAwareAgentRouter(fixture.lifecycles, fixture.messages)
                ),
                audit,
                ignored -> 7,
                CLOCK
        );
        PlayerBusinessCommandBinder.registerExamples(dispatcher, handler);

        PlayerCommandResult result = dispatcher.dispatch(new PlayerCommand(
                10001L,
                "session-1",
                1,
                1,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                new BattleStageClearCommand("settle-10001-1", "forest-1")
        ));
        fixture.executor.runAll();

        assertEquals(PlayerCommandStatus.ACCEPTED, result.status());
        BattleSettlementResult settlement = assertInstanceOf(BattleSettlementResult.class, results.responses.getFirst());
        assertEquals(BattleSettlementStatus.VICTORY, settlement.status());
        assertSame(agent, fixture.manager.get(10001L).orElseThrow());
        assertEquals(PlayerCommandAuditOutcome.EXECUTED, audit.last().outcome());
    }

    @Test
    void loginServiceLoadsAgentBeforeBindingSessionForDispatcher() {
        Fixture fixture = Fixture.create();
        fixture.repository.save(10001L, new PlayerProfile(10001L, SERVER_OPEN_TIME)
                .snapshot(4, 2, CLOCK.instant()));
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerLoginService loginService = new PlayerLoginService(fixture.manager, sessions);
        RecordingResultSink results = new RecordingResultSink();
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        PlayerCommandDispatcher dispatcher = dispatcher(fixture, sessions, audit, results);

        PlayerLoginResult login = loginService.login(10001L, "session-1");
        fixture.executor.runAll();
        PlayerCommandResult accepted = dispatcher.dispatch(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                1,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                new BattleStageClearCommand("settle-10001-1", "forest-1")
        ));
        fixture.executor.runAll();
        PlayerLoginResult reconnect = loginService.login(10001L, "session-2");
        PlayerCommandResult stale = dispatcher.dispatch(new PlayerCommand(
                10001L,
                login.session().sessionId(),
                login.session().epoch(),
                2,
                PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                new BattleStageClearCommand("settle-10001-2", "forest-1")
        ));

        assertEquals(1, login.session().epoch());
        assertEquals(4, login.stateRevision());
        assertEquals(2, login.eventRevision());
        assertEquals(SERVER_OPEN_TIME, login.profileCreatedAt());
        assertEquals(PlayerCommandStatus.ACCEPTED, accepted.status());
        assertEquals(BattleSettlementStatus.VICTORY,
                assertInstanceOf(BattleSettlementResult.class, results.responses.getFirst()).status());
        assertEquals(2, reconnect.session().epoch());
        assertEquals(PlayerCommandStatus.STALE_SESSION, stale.status());
        assertEquals(PlayerCommandAuditOutcome.EXECUTED, audit.records().getFirst().outcome());
    }

    @Test
    void passivateAndSavePersistsInsideMailboxThenReleasesOwner() {
        Fixture fixture = Fixture.create();
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);
        fixture.executor.runAll();
        agent.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        AtomicReference<PlayerStateSnapshot> saved = new AtomicReference<>();

        assertTrue(fixture.manager.passivateAndSave(10001L, saved::set));
        fixture.executor.runAll();

        assertEquals(30, saved.get().bag().itemCounts().get("gold"));
        assertEquals(saved.get(), fixture.repository.load(10001L).orElseThrow());
        assertEquals(0, fixture.manager.loadedAgents());
        assertTrue(fixture.directory.locate(AgentIdentity.player(10001L)).isEmpty());
        assertEquals(AgentLifecycleState.PASSIVATED,
                fixture.lifecycles.record(AgentIdentity.player(10001L)).orElseThrow().state());
    }

    @Test
    void saveAllLoadedPersistsSnapshotsWithoutReleasingOwners() {
        Fixture fixture = Fixture.create();
        PlayerGameAgent first = fixture.manager.getOrCreate(10001L);
        fixture.manager.getOrCreate(10002L);
        fixture.executor.runAll();
        first.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        List<PlayerStateSnapshot> saved = new ArrayList<>();

        int submitted = fixture.manager.saveAllLoaded(saved::add);
        fixture.executor.runAll();

        assertEquals(2, submitted);
        assertEquals(2, saved.size());
        assertEquals(2, fixture.manager.loadedAgents());
        assertEquals(30, fixture.repository.load(10001L).orElseThrow().bag().itemCounts().get("gold"));
        assertTrue(fixture.repository.load(10002L).isPresent());
        assertTrue(fixture.directory.locate(AgentIdentity.player(10001L)).isPresent());
        assertTrue(fixture.directory.locate(AgentIdentity.player(10002L)).isPresent());
    }

    @Test
    void autoSaveRunSubmitsMailboxSnapshotsForLoadedPlayers() {
        Fixture fixture = Fixture.create();
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);
        fixture.executor.runAll();
        agent.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        PlayerAutoSaveScheduler autoSaves = new PlayerAutoSaveScheduler(
                fixture.manager,
                Duration.ZERO,
                Duration.ofSeconds(60)
        );

        try {
            assertEquals(1, autoSaves.runOnce());
            fixture.executor.runAll();

            assertEquals(1, autoSaves.stats().runs());
            assertEquals(1, autoSaves.stats().submitted());
            assertEquals(1, autoSaves.stats().completed());
            assertEquals(0, autoSaves.stats().failedRuns());
            assertEquals(0, autoSaves.stats().failedSaves());
            assertEquals(30, fixture.repository.load(10001L).orElseThrow().bag().itemCounts().get("gold"));
            assertEquals(1, fixture.manager.loadedAgents());
        } finally {
            autoSaves.close();
        }
    }

    @Test
    void actorScheduledAutoSaveSubmitsPlayerSaveAfterSchedulerMailboxRuns() throws InterruptedException {
        Fixture fixture = Fixture.create();
        PlayerGameAgent agent = fixture.manager.getOrCreate(10001L);
        fixture.executor.runAll();
        agent.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        fixture.executor.runAll();

        try (ActorScheduleRegistry schedules = new ActorScheduleRegistry(fixture.actors);
             PlayerAutoSaveScheduler autoSaves = new PlayerAutoSaveScheduler(
                     fixture.manager,
                     schedules,
                     fixture.actors.actor("player-autosave:r1-game-1"),
                     Duration.ofMillis(1),
                     Duration.ofSeconds(60)
             )) {
            autoSaves.start();

            assertTrue(awaitQueued(fixture.actors, 1));
            assertTrue(fixture.repository.load(10001L).isEmpty());
            assertEquals(1, schedules.stats().deliveredTimerMessages());

            fixture.executor.runAll();

            assertEquals(1, autoSaves.stats().runs());
            assertEquals(1, autoSaves.stats().submitted());
            assertEquals(1, autoSaves.stats().completed());
            assertEquals(30, fixture.repository.load(10001L).orElseThrow().bag().itemCounts().get("gold"));
        }
    }

    @Test
    void autoSaveRunCountsSubmissionFailure() {
        Fixture fixture = Fixture.create();
        fixture.manager.getOrCreate(10001L);
        fixture.executor.runAll();
        PlayerAutoSaveScheduler autoSaves = new PlayerAutoSaveScheduler(
                fixture.manager,
                Duration.ZERO,
                Duration.ofSeconds(60)
        );

        try {
            fixture.actors.close();

            assertThrows(RuntimeException.class, autoSaves::runOnce);

            assertEquals(1, autoSaves.stats().runs());
            assertEquals(0, autoSaves.stats().submitted());
            assertEquals(0, autoSaves.stats().completed());
            assertEquals(1, autoSaves.stats().failedRuns());
            assertEquals(0, autoSaves.stats().failedSaves());
        } finally {
            autoSaves.close();
        }
    }

    @Test
    void autoSaveCountsMailboxPersistenceFailure() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
        InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
        AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                actors,
                directory,
                CLOCK
        );
        InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
        assertEquals(GameConfigPublishStatus.PUBLISHED,
                configs.publish(ExampleGameConfigs.basic(7, CLOCK.instant())).status());
        PlayerGameAgentManager manager = new PlayerGameAgentManager(
                actors,
                messages,
                new FailingPlayerStateRepository(),
                configs,
                lifecycles,
                CLOCK,
                SERVER_OPEN_TIME
        );
        PlayerAutoSaveScheduler autoSaves = new PlayerAutoSaveScheduler(
                manager,
                Duration.ZERO,
                Duration.ofSeconds(60)
        );
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(manager);
        registry.register(autoSaves);

        try {
            manager.getOrCreate(10001L);
            executor.runAll();
            assertEquals(1, autoSaves.runOnce());
            executor.runAll();
            RuntimeHealthProbe probe = new RuntimeHealthProbe(
                    CLOCK,
                    actors,
                    lifecycles,
                    new InMemoryVersionedEventOutbox(CLOCK),
                    new ClusterDirectory(new InMemoryServiceRegistry()),
                    registry,
                    RuntimeHealthPolicy.defaults()
            );
            RuntimeHealthSnapshot snapshot = probe.snapshot();

            assertEquals(1, autoSaves.stats().runs());
            assertEquals(1, autoSaves.stats().submitted());
            assertEquals(0, autoSaves.stats().completed());
            assertEquals(0, autoSaves.stats().failedRuns());
            assertEquals(1, autoSaves.stats().failedSaves());
            assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
            assertEquals(1, snapshot.playerAgents().autoSaveFailedSaves());
            assertTrue(RuntimeHealthJsonFormatter.format(snapshot).contains("\"autoSaveFailedSaves\":1"));
            assertTrue(RuntimeMetricsFormatter.format(snapshot)
                    .contains("commonbattle_player_autosave_failed_saves_total 1"));
        } finally {
            autoSaves.close();
            actors.close();
        }
    }

    @Test
    void healthProbeDegradesWhenAutoSaveRunFails() {
        ActorSystem actors = new ActorSystem(Runnable::run, 64);
        PlayerAutoSaveScheduler autoSaves = new PlayerAutoSaveScheduler(
                callback -> {
                    throw new IllegalStateException("save target unavailable");
                },
                Duration.ZERO,
                Duration.ofSeconds(60)
        );
        RuntimeHealthRegistry registry = new RuntimeHealthRegistry();
        registry.register(autoSaves);

        try {
            assertThrows(IllegalStateException.class, autoSaves::runOnce);
            RuntimeHealthProbe probe = new RuntimeHealthProbe(
                    CLOCK,
                    actors,
                    new AgentLifecycleManager(
                            ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                            actors,
                            new InMemoryAgentDirectory(),
                            CLOCK
                    ),
                    new InMemoryVersionedEventOutbox(CLOCK),
                    new ClusterDirectory(new InMemoryServiceRegistry()),
                    registry,
                    RuntimeHealthPolicy.defaults()
            );
            RuntimeHealthSnapshot snapshot = probe.snapshot();
            String json = RuntimeHealthJsonFormatter.format(snapshot);
            String metrics = RuntimeMetricsFormatter.format(snapshot);

            assertEquals(RuntimeHealthStatus.DEGRADED, snapshot.status());
            assertEquals(1, snapshot.playerAgents().autoSaveSchedulers());
            assertEquals(1, snapshot.playerAgents().autoSaveFailedRuns());
            assertEquals(0, snapshot.playerAgents().autoSaveFailedSaves());
            assertTrue(json.contains("\"autoSaveFailedRuns\":1"));
            assertTrue(metrics.contains("commonbattle_player_autosave_failed_runs_total 1"));
        } finally {
            autoSaves.close();
            actors.close();
        }
    }

    @Test
    void drainServicePassivatesAndSavesAllLoadedPlayers() {
        Fixture fixture = Fixture.create();
        PlayerGameAgent first = fixture.manager.getOrCreate(10001L);
        fixture.manager.getOrCreate(10002L);
        fixture.executor.runAll();
        first.clearBattleStage("settle-10001-1", "forest-1", ignored -> {
        });
        PlayerAgentDrainService drain = new PlayerAgentDrainService(fixture.manager);

        drain.beginDrain();
        fixture.executor.runAll();

        assertTrue(drain.isDraining());
        assertEquals(2, drain.submitted());
        assertEquals(2, drain.completed());
        assertEquals(0, fixture.manager.loadedAgents());
        assertEquals(30, fixture.repository.load(10001L).orElseThrow().bag().itemCounts().get("gold"));
        assertTrue(fixture.repository.load(10002L).isPresent());
        assertTrue(fixture.directory.locate(AgentIdentity.player(10001L)).isEmpty());
        assertTrue(fixture.directory.locate(AgentIdentity.player(10002L)).isEmpty());
    }

    @Test
    void staleLogoutDoesNotPassivateReconnectedPlayer() {
        Fixture fixture = Fixture.create();
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerLoginService loginService = new PlayerLoginService(fixture.manager, sessions);
        PlayerLoginResult first = loginService.login(10001L, "session-1");
        fixture.executor.runAll();
        PlayerLoginResult second = loginService.login(10001L, "session-2");
        AtomicReference<PlayerStateSnapshot> saved = new AtomicReference<>();

        boolean staleLogout = loginService.logoutAndPassivate(first.session(), saved::set);

        assertTrue(fixture.manager.get(10001L).isPresent());
        assertEquals(2, second.session().epoch());
        assertNull(saved.get());
        assertFalse(staleLogout);
    }

    @Test
    void loginServiceRejectsNewLoginWhileDraining() {
        Fixture fixture = Fixture.create();
        InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
        PlayerLoginService loginService = new PlayerLoginService(fixture.manager, sessions);

        loginService.beginDrain();

        assertTrue(loginService.isDraining());
        assertThrows(PlayerLoginDrainingException.class, () -> loginService.login(10001L, "session-1"));
        assertEquals(0, fixture.manager.loadedAgents());

        loginService.resumeAccepting();
        PlayerLoginResult login = loginService.login(10001L, "session-1");

        fixture.executor.runAll();
        assertEquals(1, login.session().epoch());
        assertEquals(1, fixture.manager.loadedAgents());
    }

    private record Fixture(
            RecordingExecutor executor,
            ActorSystem actors,
            DefaultAgentMessagePort messages,
            InMemoryPlayerStateRepository repository,
            InMemoryAgentDirectory directory,
            AgentLifecycleManager lifecycles,
            PlayerGameAgentManager manager
    ) {
        private static Fixture create() {
            return create(null);
        }

        private static Fixture create(EventPublisher publisher) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            InMemoryPlayerStateRepository repository = new InMemoryPlayerStateRepository();
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK
            );
            InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
            assertEquals(GameConfigPublishStatus.PUBLISHED,
                    configs.publish(ExampleGameConfigs.basic(7, CLOCK.instant())).status());
            PlayerGameAgentManager manager = new PlayerGameAgentManager(
                    actors,
                    messages,
                    repository,
                    configs,
                    lifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME,
                    publisher
            );
            return new Fixture(executor, actors, messages, repository, directory, lifecycles, manager);
        }
    }

    private static PlayerCommandDispatcher dispatcher(
            Fixture fixture,
            InMemoryPlayerSessionRegistry sessions,
            InMemoryPlayerCommandAuditLog audit,
            RecordingResultSink results
    ) {
        PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                sessions,
                new PlayerCommandSequencer(),
                new AdmissionControlledAgentRouter(
                        (target, operation) -> AdmissionDecision.accept(),
                        new LifecycleAwareAgentRouter(fixture.lifecycles, fixture.messages)
                ),
                audit,
                ignored -> 7,
                CLOCK
        );
        PlayerBusinessCommandBinder.registerExamples(
                dispatcher,
                new PlayerBusinessCommandHandler(fixture.manager::getOrCreate, results)
        );
        return dispatcher;
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        private final List<VersionedEvent> events = new ArrayList<>();

        @Override
        public void publish(VersionedEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingResultSink implements PlayerBusinessResultSink {
        private final List<Object> responses = new ArrayList<>();

        @Override
        public void succeeded(PlayerCommand command, Object response) {
            responses.add(response);
        }

        @Override
        public void failed(PlayerCommand command, Throwable error) {
            throw new AssertionError(error);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        synchronized void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }

    private static boolean awaitQueued(ActorSystem actors, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (actors.stats().queuedTasks() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return actors.stats().queuedTasks() >= expected;
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }

    private static final class FailingPlayerStateRepository implements PlayerStateRepository {
        @Override
        public Optional<PlayerStateSnapshot> load(Long key) {
            return Optional.empty();
        }

        @Override
        public void save(Long key, PlayerStateSnapshot snapshot) {
            throw new IllegalStateException("player state store unavailable");
        }
    }
}
