package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
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
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;
import com.commonbattle.cluster.rpc.RpcTimeoutException;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientCommandResponse;
import com.commonbattle.game.session.PlayerClientErrorCode;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryMode;
import com.commonbattle.game.session.PlayerOutboundMessage;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
import com.commonbattle.game.session.PlayerOutboundTopicPolicy;
import com.commonbattle.game.session.PlayerSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerClientCommandIngressTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void acceptedClientCommandRespondsAfterPlayerMailboxRuns() {
        Fixture fixture = Fixture.create();
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        PlayerClientCommandEnvelope envelope = fixture.command(1);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        PlayerClientCommandAcceptResult result = ingress.accept(envelope);

        assertEquals(true, result.accepted());
        assertEquals(0, fixture.handled.get());
        assertEquals(0, written.size());
        assertEquals(1, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(1, fixture.handled.get());
        assertEquals(1, written.size());
        assertEquals(PlayerClientCommandIngress.RESPONSE_TOPIC, written.getFirst().topic());
        PlayerClientCommandResponse response = (PlayerClientCommandResponse) written.getFirst().payload();
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status());
        assertEquals(PlayerBusinessAck.OK, response.payload());
        assertEquals(PlayerBusinessResponse.OK, response.code());
        assertEquals(false, response.replayed());
    }

    @Test
    void staleClientCommandReturnsFailureWithoutEnteringMailbox() {
        Fixture fixture = Fixture.create();
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        PlayerClientCommandAcceptResult result = ingress.accept(
                new PlayerClientCommandEnvelope(10001L, "old-session", 1, 1, "test.echo", "payload"));

        assertEquals(false, result.accepted());
        assertEquals(false, result.closeConnection());
        assertEquals(PlayerClientErrorCode.SESSION_EXPIRED, result.code());
        assertEquals(0, fixture.handled.get());
        assertEquals(0, fixture.executor.queued());
        PlayerClientCommandResponse response = (PlayerClientCommandResponse) written.getFirst().payload();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals("STALE_SESSION", response.code());
        assertEquals(false, response.replayed());
    }

    @Test
    void rateLimitedClientCommandResponseCarriesRetryAfter() {
        Fixture fixture = Fixture.create((target, operation) ->
                AdmissionDecision.reject("rate_limited", Duration.ofMillis(100)));
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        PlayerClientCommandAcceptResult result = ingress.accept(fixture.command(1));

        assertEquals(false, result.accepted());
        assertEquals(false, result.closeConnection());
        assertEquals(PlayerClientErrorCode.COMMAND_RATE_LIMITED, result.code());
        assertEquals(100, result.retryAfter().toMillis());
        assertEquals(0, fixture.handled.get());
        assertEquals(0, fixture.executor.queued());
        PlayerClientCommandResponse response = (PlayerClientCommandResponse) written.getFirst().payload();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals("RATE_LIMITED", response.code());
        assertEquals("rate_limited", response.message());
        assertEquals(100, response.retryAfterMillis());
    }

    @Test
    void mailboxPressureClientCommandResponseCarriesRetryAfter() {
        Fixture fixture = Fixture.create((target, operation) ->
                AdmissionDecision.reject("mailbox_pressure:target", Duration.ofMillis(50)));
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        PlayerClientCommandAcceptResult result = ingress.accept(fixture.command(1));

        assertEquals(false, result.accepted());
        assertEquals(false, result.closeConnection());
        assertEquals(PlayerClientErrorCode.COMMAND_BACKPRESSURED, result.code());
        assertEquals(50, result.retryAfter().toMillis());
        PlayerClientCommandResponse response = (PlayerClientCommandResponse) written.getFirst().payload();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals("BACKPRESSURED", response.code());
        assertEquals("mailbox_pressure:target", response.message());
        assertEquals(50, response.retryAfterMillis());
    }

    @Test
    void remoteTimeoutClientCommandResponseIsCountedAsIngressReject() {
        Duration timeout = Duration.ofMillis(35);
        Fixture fixture = Fixture.createRemoteOwner(new FailingRpcGateway(
                request -> new RpcTimeoutException(request, timeout)
        ));
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        PlayerClientCommandAcceptResult result = ingress.accept(fixture.command(1));

        assertEquals(false, result.accepted());
        assertEquals(false, result.closeConnection());
        assertEquals(PlayerClientErrorCode.COMMAND_REMOTE_TIMEOUT, result.code());
        assertEquals(timeout.toMillis(), result.retryAfter().toMillis());
        assertEquals(0, fixture.handled.get());
        assertEquals(0, fixture.executor.queued());
        PlayerClientCommandResponse response = (PlayerClientCommandResponse) written.getFirst().payload();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals(PlayerBusinessResponse.REMOTE_TIMEOUT, response.code());
        assertEquals(timeout.toMillis(), response.retryAfterMillis());
    }

    @Test
    void remoteUnavailableClientCommandResponseIsCountedAsIngressReject() {
        Fixture fixture = Fixture.createRemoteOwner(new FailingRpcGateway(RpcNoRoutableServiceException::new));
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        PlayerClientCommandAcceptResult result = ingress.accept(fixture.command(1));

        assertEquals(false, result.accepted());
        assertEquals(false, result.closeConnection());
        assertEquals(PlayerClientErrorCode.COMMAND_REMOTE_UNAVAILABLE, result.code());
        assertEquals(0, fixture.handled.get());
        assertEquals(0, fixture.executor.queued());
        PlayerClientCommandResponse response = (PlayerClientCommandResponse) written.getFirst().payload();
        assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
        assertEquals(PlayerBusinessResponse.REMOTE_UNAVAILABLE, response.code());
    }

    @Test
    void duplicateClientCommandReplaysCompletedResponseWithFlag() {
        Fixture fixture = Fixture.create();
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound);
        PlayerClientCommandEnvelope envelope = fixture.command(1);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        ingress.accept(envelope);
        fixture.executor.runNext();
        ingress.accept(envelope);

        assertEquals(1, fixture.handled.get());
        assertEquals(2, written.size());
        PlayerClientCommandResponse first = (PlayerClientCommandResponse) written.get(0).payload();
        PlayerClientCommandResponse second = (PlayerClientCommandResponse) written.get(1).payload();
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.status());
        assertEquals(false, first.replayed());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, second.status());
        assertEquals(true, second.replayed());
        assertEquals(PlayerBusinessAck.OK, second.payload());
    }

    @Test
    void commandResponseUsesTopicPolicy() {
        Fixture fixture = Fixture.create();
        PlayerOutboundTopicPolicies policies = new PlayerOutboundTopicPolicies()
                .register(PlayerClientCommandIngress.RESPONSE_TOPIC, PlayerOutboundTopicPolicy.bestEffort());
        PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(fixture.commands, fixture.outbound, policies);
        PlayerClientCommandEnvelope envelope = fixture.command(1);
        List<PlayerOutboundMessage> written = new ArrayList<>();
        fixture.outbound.connect(fixture.session, message -> {
            written.add(message);
            return true;
        });

        ingress.accept(envelope);
        fixture.executor.runNext();

        assertEquals(1, written.size());
        assertEquals(PlayerOutboundDeliveryMode.BEST_EFFORT, written.getFirst().mode());
        assertEquals(List.of(), fixture.outbound.pendingAck(10001L));
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerSession session,
            PlayerBusinessCommandGateway commands,
            PlayerOutboundDeliveryHub outbound,
            AtomicInteger handled
    ) {
        private static Fixture create() {
            return create((target, operation) -> AdmissionDecision.accept());
        }

        private static Fixture create(com.commonbattle.actor.backpressure.InboundAdmissionController admissions) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef player = actors.actor("player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK
            );
            lifecycles.activate(AgentIdentity.player(10001L), player.id());
            executor.runNext();
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            PlayerSession session = sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            admissions,
                            new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, new NoopRpcGateway()))
                    )
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            AtomicInteger handled = new AtomicInteger();
            dispatcher.handle("test.echo", (context, command) -> {
                handled.incrementAndGet();
                responses.succeeded(command, PlayerBusinessAck.OK);
            });
            PlayerBusinessCommandGateway commands = new PlayerBusinessCommandGateway(
                    dispatcher,
                    new NoopRpcGateway(),
                    responses,
                    Duration.ofSeconds(1),
                    new DirectScheduler()
            );
            PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                    sessions,
                    CLOCK,
                    8,
                    PlayerDeliveryOverflowStrategy.DROP_OLDEST
            );
            return new Fixture(executor, session, commands, outbound, handled);
        }

        private static Fixture createRemoteOwner(RpcGateway rpc) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef remoteRef = actors.actor("remote-player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            ServiceId local = ServiceId.of(ServiceKind.GAME, "r1", "game-1");
            ServiceId remote = ServiceId.of(ServiceKind.GAME, "r1", "game-2");
            directory.claim(AgentIdentity.player(10001L), new AgentLocation(remote, remoteRef));
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    local,
                    actors,
                    directory,
                    CLOCK
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            PlayerSession session = sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, new DefaultAgentMessagePort(actors, rpc))
                    )
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            AtomicInteger handled = new AtomicInteger();
            dispatcher.handle("test.echo", (context, command) -> {
                handled.incrementAndGet();
                responses.succeeded(command, PlayerBusinessAck.OK);
            });
            PlayerBusinessCommandGateway commands = new PlayerBusinessCommandGateway(
                    dispatcher,
                    rpc,
                    responses,
                    Duration.ofSeconds(1),
                    new DirectScheduler()
            );
            PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                    sessions,
                    CLOCK,
                    8,
                    PlayerDeliveryOverflowStrategy.DROP_OLDEST
            );
            return new Fixture(executor, session, commands, outbound, handled);
        }

        private PlayerClientCommandEnvelope command(long sequence) {
            return new PlayerClientCommandEnvelope(10001L, session.sessionId(), session.epoch(), sequence, "test.echo", "payload");
        }
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

    private static final class FailingRpcGateway implements RpcGateway {
        private final FailureFactory failureFactory;

        private FailingRpcGateway(FailureFactory failureFactory) {
            this.failureFactory = failureFactory;
        }

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            callback.failure(failureFactory.create(request));
        }
    }

    @FunctionalInterface
    private interface FailureFactory {
        Throwable create(RpcRequest<?> request);
    }

    private static final class DirectScheduler extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private DirectScheduler() {
            super(1, runnable -> {
                Thread thread = new Thread(runnable, "test-player-client-command-timeout");
                thread.setDaemon(true);
                return thread;
            });
        }
    }
}
