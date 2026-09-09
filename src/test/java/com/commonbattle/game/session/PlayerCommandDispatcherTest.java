package com.commonbattle.game.session;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.AgentRouteType;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerCommandDispatcherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void acceptedLocalCommandRunsInsidePlayerMailbox() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());
        AtomicInteger handled = new AtomicInteger();
        fixture.dispatcher.handle("bag.use", (context, command) -> handled.incrementAndGet());

        PlayerCommandResult result = fixture.dispatch(command(1));

        assertEquals(PlayerCommandStatus.ACCEPTED, result.status());
        assertEquals(0, handled.get());
        assertEquals(1, fixture.actors.stats().queuedTasksByCategory().get(ActorTaskCategory.PLAYER_COMMAND));
        fixture.executor.runNext();
        assertEquals(1, handled.get());
    }

    @Test
    void duplicateAndGapCommandsDoNotEnterMailbox() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());
        AtomicInteger handled = new AtomicInteger();
        fixture.dispatcher.handle("bag.use", (context, command) -> handled.incrementAndGet());

        fixture.dispatch(command(1));
        fixture.executor.runNext();
        PlayerCommandResult duplicate = fixture.dispatch(command(1));
        PlayerCommandResult gap = fixture.dispatch(command(3));

        assertEquals(PlayerCommandStatus.DUPLICATE, duplicate.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, duplicate.originalStatus().orElseThrow());
        assertEquals("duplicate:ACCEPTED", duplicate.reason());
        assertEquals(PlayerCommandStatus.GAP, gap.status());
        assertEquals(1, handled.get());
        assertEquals(0, fixture.executor.queued());
    }

    @Test
    void staleSessionIsRejectedBeforeSequenceAndMailbox() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());
        fixture.sessions.bind(10001L, "session-new");
        fixture.dispatcher.handle("bag.use", (context, command) -> {
            throw new AssertionError("stale command must not run");
        });

        PlayerCommandResult result = fixture.dispatch(command(1));

        assertEquals(PlayerCommandStatus.STALE_SESSION, result.status());
        assertEquals(0, fixture.executor.queued());
    }

    @Test
    void drainingDispatcherRejectsBeforeSequenceAndCanResume() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());
        AtomicInteger handled = new AtomicInteger();
        fixture.dispatcher.handle("bag.use", (context, command) -> handled.incrementAndGet());

        fixture.dispatcher.beginDrain();
        PlayerCommandResult rejected = fixture.dispatch(command(1));
        fixture.dispatcher.resumeAccepting();
        PlayerCommandResult accepted = fixture.dispatch(command(1));
        fixture.executor.runNext();

        assertEquals(PlayerCommandStatus.DRAINING, rejected.status());
        assertEquals("server_draining", rejected.reason());
        assertEquals(PlayerCommandStatus.ACCEPTED, accepted.status());
        assertEquals(1, handled.get());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.DRAINING));
        assertEquals(1, fixture.dispatcher.stats().acceptingDispatchers());
        assertEquals(0, fixture.dispatcher.stats().drainingDispatchers());
    }

    @Test
    void rateLimitDoesNotConsumeCommandSequence() {
        AtomicInteger attempts = new AtomicInteger();
        Fixture fixture = Fixture.local((target, operation) -> attempts.incrementAndGet() == 1
                ? AdmissionDecision.reject("rate_limited", Duration.ofMillis(100))
                : AdmissionDecision.accept());
        AtomicInteger handled = new AtomicInteger();
        fixture.dispatcher.handle("bag.use", (context, command) -> handled.incrementAndGet());

        PlayerCommandResult rejected = fixture.dispatch(command(1));
        PlayerCommandResult retry = fixture.dispatch(command(1));
        fixture.executor.runNext();

        assertEquals(PlayerCommandStatus.RATE_LIMITED, rejected.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, retry.status());
        assertEquals(1, handled.get());
    }

    @Test
    void migratingPlayerCommandIsRejectedWithExplicitStatus() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());
        fixture.dispatcher.handle("bag.use", (context, command) -> {
            throw new AssertionError("migrating player command must not enter mailbox");
        });
        AgentIdentity player = AgentIdentity.player(10001L);
        AgentLocation target = new AgentLocation(
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new ActorRef("player-10001")
        );

        fixture.lifecycles.migrate(player, target, ignored -> {
        });
        PlayerCommandResult result = fixture.dispatch(command(1));

        assertEquals(PlayerCommandStatus.AGENT_MIGRATING, result.status());
        assertEquals("agent_migrating", result.reason());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.AGENT_MIGRATING));
        assertEquals(1, fixture.executor.queued());
    }

    @Test
    void mailboxFullDoesNotConsumeCommandSequence() {
        Fixture fixture = Fixture.local(
                (target, operation) -> AdmissionDecision.accept(),
                PlayerCommandAuditSink.NOOP,
                () -> 0,
                new ActorSystemConfig(1, 64, 1, ActorOverflowStrategy.REJECT, Duration.ZERO)
        );
        AtomicInteger handled = new AtomicInteger();
        fixture.dispatcher.handle("bag.use", (context, command) -> handled.incrementAndGet());
        fixture.actors.send(new ActorRef("player-10001"), ignored -> {
        });

        PlayerCommandResult rejected = fixture.dispatch(command(1));
        fixture.executor.runNext();
        PlayerCommandResult retry = fixture.dispatch(command(1));
        fixture.executor.runNext();

        assertEquals(PlayerCommandStatus.MAILBOX_FULL, rejected.status());
        assertEquals(PlayerCommandStatus.ACCEPTED, retry.status());
        assertEquals(1, handled.get());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.MAILBOX_FULL));
    }

    @Test
    void remoteOwnerReturnsRouteWithoutLocalExecution() {
        Fixture fixture = Fixture.remote();
        fixture.dispatcher.handle("bag.use", (context, command) -> {
            throw new AssertionError("remote command must not run locally");
        });

        PlayerCommandResult result = fixture.dispatch(command(1));

        assertEquals(PlayerCommandStatus.ROUTED_REMOTE, result.status());
        assertEquals(AgentRouteType.REMOTE, result.route().orElseThrow().type());
        assertEquals(0, fixture.executor.queued());
    }

    @Test
    void unknownOperationIsRejected() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());

        PlayerCommandResult result = fixture.dispatch(command(1));

        assertEquals(PlayerCommandStatus.UNKNOWN_OPERATION, result.status());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.UNKNOWN_OPERATION));
    }

    @Test
    void dispatcherStatsCountsCommandResults() {
        Fixture fixture = Fixture.local((target, operation) -> AdmissionDecision.accept());
        fixture.dispatcher.handle("bag.use", (context, command) -> {
        });

        fixture.dispatch(command(1));
        fixture.executor.runNext();
        fixture.dispatch(command(1));
        fixture.dispatch(command(3));

        PlayerCommandStats stats = fixture.dispatcher.stats();
        assertEquals(1, stats.count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, stats.count(PlayerCommandStatus.DUPLICATE));
        assertEquals(1, stats.count(PlayerCommandStatus.GAP));
    }

    @Test
    void auditRecordsConfigVersionWhenLocalCommandActuallyRuns() {
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        AtomicLong configVersion = new AtomicLong(1);
        Fixture fixture = Fixture.local(
                (target, operation) -> AdmissionDecision.accept(),
                audit,
                configVersion::get
        );
        fixture.dispatcher.handle("bag.use", (context, command) -> {
        });

        fixture.dispatch(command(1));
        configVersion.set(2);
        fixture.executor.runNext();

        PlayerCommandAuditRecord record = audit.last();
        assertEquals(PlayerCommandAuditOutcome.EXECUTED, record.outcome());
        assertEquals(PlayerCommandStatus.ACCEPTED, record.dispatchStatus());
        assertEquals(2, record.configVersion());
        assertEquals("bag.use", record.operation());
    }

    @Test
    void auditRecordsRejectedCommandWithoutConfigVersion() {
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        Fixture fixture = Fixture.local(
                (target, operation) -> AdmissionDecision.reject("rate_limited", Duration.ofMillis(100)),
                audit,
                () -> 9
        );
        fixture.dispatcher.handle("bag.use", (context, command) -> {
        });

        PlayerCommandResult result = fixture.dispatch(command(1));

        PlayerCommandAuditRecord record = audit.last();
        assertEquals(PlayerCommandStatus.RATE_LIMITED, result.status());
        assertEquals(PlayerCommandAuditOutcome.REJECTED, record.outcome());
        assertEquals(PlayerCommandStatus.RATE_LIMITED, record.dispatchStatus());
        assertEquals(0, record.configVersion());
        assertEquals("rate_limited", record.reason());
        assertEquals(0, fixture.executor.queued());
    }

    @Test
    void auditRecordsHandlerFailureWithExecutionConfigVersion() {
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog();
        Fixture fixture = Fixture.local(
                (target, operation) -> AdmissionDecision.accept(),
                audit,
                () -> 5
        );
        fixture.dispatcher.handle("bag.use", (context, command) -> {
            throw new IllegalStateException("boom");
        });
        fixture.dispatch(command(1));

        fixture.executor.runNext();

        PlayerCommandAuditRecord record = audit.last();
        assertEquals(PlayerCommandAuditOutcome.FAILED, record.outcome());
        assertEquals(PlayerCommandStatus.ACCEPTED, record.dispatchStatus());
        assertEquals(5, record.configVersion());
        assertEquals("boom", record.reason());
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(10001L, "session-1", 1, sequence, "bag.use", "");
    }

    private static final class Fixture {
        private final RecordingExecutor executor;
        private final ActorSystem actors;
        private final InMemoryPlayerSessionRegistry sessions;
        private final PlayerCommandDispatcher dispatcher;
        private final AgentLifecycleManager lifecycles;

        private Fixture(
                RecordingExecutor executor,
                ActorSystem actors,
                InMemoryPlayerSessionRegistry sessions,
                PlayerCommandDispatcher dispatcher,
                AgentLifecycleManager lifecycles
        ) {
            this.executor = executor;
            this.actors = actors;
            this.sessions = sessions;
            this.dispatcher = dispatcher;
            this.lifecycles = lifecycles;
        }

        private PlayerCommandResult dispatch(PlayerCommand command) {
            return dispatcher.dispatch(command);
        }

        private static Fixture local(com.commonbattle.actor.backpressure.InboundAdmissionController admissions) {
            return local(admissions, PlayerCommandAuditSink.NOOP, () -> 0);
        }

        private static Fixture local(
                com.commonbattle.actor.backpressure.InboundAdmissionController admissions,
                PlayerCommandAuditSink auditSink,
                ConfigVersionSupplier configVersion
        ) {
            return local(admissions, auditSink, configVersion, ActorSystemConfig.defaults(1).withBatchSize(64));
        }

        private static Fixture local(
                com.commonbattle.actor.backpressure.InboundAdmissionController admissions,
                PlayerCommandAuditSink auditSink,
                ConfigVersionSupplier configVersion,
                ActorSystemConfig actorConfig
        ) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(
                    executor,
                    actorConfig,
                    ignored -> {
                    },
                    ignored -> {
                    }
            );
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            lifecycles.activate(AgentIdentity.player(10001L), "player-10001");
            executor.runNext();
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            admissions,
                            new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, new NoopRpcGateway()))
                    ),
                    auditSink,
                    ignored -> configVersion.getAsLong(),
                    CLOCK
            );
            return new Fixture(executor, actors, sessions, dispatcher, lifecycles);
        }

        private static Fixture remote() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            ServiceId local = ServiceId.of(ServiceKind.SCENE, "r1", "scene-1");
            ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
            directory.claim(AgentIdentity.player(10001L), new AgentLocation(remote, new ActorRef("player-10001")));
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, new NoopRpcGateway()))
                    )
            );
            return new Fixture(executor, actors, sessions, dispatcher, lifecycles);
        }
    }

    @FunctionalInterface
    private interface ConfigVersionSupplier {
        long getAsLong();
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

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
