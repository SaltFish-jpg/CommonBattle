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
import com.commonbattle.cluster.rpc.RpcNoRoutableServiceException;
import com.commonbattle.cluster.rpc.RpcStructuredException;
import com.commonbattle.cluster.rpc.RpcTimeoutException;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
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
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerCommandStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerBusinessCommandGatewayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void localSubmitWaitsForMailboxBusinessResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();
        PlayerCommand command = command(1);

        fixture.commands.submit(command, callback);

        assertNull(callback.response.get());
        assertEquals(1, fixture.responses.pendingResponses());
        fixture.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
        assertEquals(PlayerBusinessAck.OK, callback.response.get().payload());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void duplicateSubmitWhilePendingSharesMailboxResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback first = new RecordingCallback();
        RecordingCallback retry = new RecordingCallback();
        PlayerCommand command = command(1);

        fixture.commands.submit(command, first);
        fixture.commands.submit(command, retry);

        assertNull(first.response.get());
        assertNull(retry.response.get());
        assertEquals(1, fixture.responses.pendingResponses());
        assertEquals(1, fixture.responses.stats().sharedWaiters());
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.ACCEPTED));
        assertEquals(1, fixture.dispatcher.stats().count(PlayerCommandStatus.DUPLICATE));

        fixture.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, retry.response.get().status());
        assertEquals(PlayerBusinessAck.OK, first.response.get().payload());
        assertEquals(PlayerBusinessAck.OK, retry.response.get().payload());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(1, fixture.responses.stats().completedResponses());
    }

    @Test
    void duplicateSubmitAfterCompletionReplaysCachedResponse() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback first = new RecordingCallback();
        RecordingCallback retry = new RecordingCallback();
        PlayerCommand command = command(1);

        fixture.commands.submit(command, first);
        fixture.executor.runAll();
        fixture.commands.submit(command, retry);

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.response.get().status());
        assertEquals(PlayerBusinessResponseStatus.SUCCESS, retry.response.get().status());
        assertEquals(PlayerBusinessAck.OK, retry.response.get().payload());
        assertEquals(0, fixture.responses.pendingResponses());
        assertEquals(0, fixture.executor.queued());
        assertEquals(1, fixture.responses.stats().completedResponses());
        assertEquals(1, fixture.responses.stats().replayedResponses());
        assertEquals(1, fixture.responses.stats().cachedResponses());
    }

    @Test
    void remoteEndpointRespondsAfterOwnerMailboxExecutesCommand() {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        LocalClusterTransport transport = new LocalClusterTransport();
        ClusterTopology topology = new ClusterTopology()
                .allow(ServiceKind.GAME, ServiceKind.GAME);
        ServiceDescriptor gameOne = descriptor("game-1", 9101);
        ServiceDescriptor gameTwo = descriptor("game-2", 9102);
        registry.register(gameOne);
        registry.register(gameTwo);
        ClusterDirectory gameOneDirectory = new ClusterDirectory(registry);
        gameOneDirectory.seed(gameTwo);
        ClusterDirectory gameTwoDirectory = new ClusterDirectory(registry);
        gameTwoDirectory.seed(gameOne);
        ClusterRpcGateway gameOneGateway = new ClusterRpcGateway(gameOne, gameOneDirectory, topology, transport);
        ClusterRpcGateway gameTwoGateway = new ClusterRpcGateway(gameTwo, gameTwoDirectory, topology, transport);
        Fixture owner = Fixture.create(gameTwo.id(), gameTwoGateway);
        new PlayerBusinessCommandEndpoint(owner.commands).bind(gameTwoGateway);
        RecordingCallback callback = new RecordingCallback();

        gameOneGateway.call(
                RpcRequest.toService(
                        gameTwo.id(),
                        PlayerBusinessRpcOperations.DISPATCH,
                        command(1),
                        PlayerBusinessResponse.class
                ),
                callback
        );

        assertNull(callback.response.get());
        assertEquals(1, owner.responses.pendingResponses());
        owner.executor.runAll();

        assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
        assertEquals(PlayerBusinessAck.OK, callback.response.get().payload());
        assertEquals(1, callback.response.get().sequence());
        assertEquals(0, owner.responses.pendingResponses());
    }

    @Test
    void remoteForwardTimeoutReturnsClassifiedFailureEnvelope() {
        Duration timeout = Duration.ofMillis(25);
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new FailingRpcGateway(request -> new RpcTimeoutException(request, timeout))
        );
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals(PlayerBusinessResponse.REMOTE_TIMEOUT, callback.response.get().code());
        assertEquals(timeout.toMillis(), callback.response.get().retryAfterMillis());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void remoteForwardUnavailableReturnsClassifiedFailureEnvelope() {
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new FailingRpcGateway(RpcNoRoutableServiceException::new)
        );
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals(PlayerBusinessResponse.REMOTE_UNAVAILABLE, callback.response.get().code());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void remoteForwardStructuredFailureKeepsCodeAndRetryAfter() {
        Fixture fixture = Fixture.createRemoteOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                ServiceId.of(ServiceKind.GAME, "r1", "game-2"),
                new FailingRpcGateway(request -> new RpcStructuredException(
                        "REMOTE_MAINTENANCE",
                        "maintenance",
                        Duration.ofMillis(80)
                ))
        );
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(command(1), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("REMOTE_MAINTENANCE", callback.response.get().code());
        assertEquals("maintenance", callback.response.get().message());
        assertEquals(80, callback.response.get().retryAfterMillis());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void rejectedLocalCommandReturnsFailureEnvelopeAndClearsWaiter() {
        Fixture fixture = Fixture.create(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), new NoopRpcGateway());
        RecordingCallback callback = new RecordingCallback();

        fixture.commands.submit(new PlayerCommand(
                10001L,
                "stale-session",
                1,
                3,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        ), callback);

        assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
        assertEquals("STALE_SESSION", callback.response.get().code());
        assertEquals(0, fixture.responses.pendingResponses());
    }

    @Test
    void localSubmitTimesOutWhenMailboxDoesNotProduceResponseInTime() throws Exception {
        Fixture fixture = Fixture.create(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                new NoopRpcGateway(),
                Duration.ofMillis(10)
        );
        try {
            RecordingCallback callback = new RecordingCallback();

            fixture.commands.submit(command(1), callback);

            assertTrue(await(() -> callback.response.get() != null));
            assertEquals(PlayerBusinessResponseStatus.FAILED, callback.response.get().status());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
            assertEquals(0, fixture.responses.pendingResponses());
            assertEquals(1, fixture.responses.stats().timedOutResponses());
            fixture.executor.runAll();
            assertEquals(1, fixture.responses.stats().fallbackResponses());
            assertEquals(PlayerBusinessResponse.TIMEOUT, callback.response.get().code());
        } finally {
            fixture.commands.close();
        }
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                sequence,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        );
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(PlayerBusinessRpcOperations.DISPATCH),
                Map.of()
        );
    }

    private record Fixture(
            RecordingExecutor executor,
            PlayerBusinessResponseHub responses,
            PlayerCommandDispatcher dispatcher,
            PlayerBusinessCommandGateway commands
    ) {
        private static Fixture create(ServiceId local, RpcGateway rpc) {
            return create(local, rpc, PlayerBusinessCommandGateway.DEFAULT_RESPONSE_TIMEOUT);
        }

        private static Fixture create(ServiceId local, RpcGateway rpc, Duration timeout) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef self = actors.actor("player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            lifecycles.activate(AgentIdentity.player(10001L), self.id());
            executor.runAll();
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, rpc);
            PlayerGameAgent agent = new PlayerGameAgent(
                    messages,
                    self,
                    new PlayerProfile(10001L, SERVER_OPEN_TIME),
                    activityService(),
                    growthService(),
                    CLOCK,
                    SERVER_OPEN_TIME
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    ),
                    new InMemoryPlayerCommandAuditLog(),
                    ignored -> 1,
                    CLOCK
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            PlayerBusinessCommandBinder.registerExamples(
                    dispatcher,
                    new PlayerBusinessCommandHandler(playerId -> agent, responses)
            );
            return new Fixture(executor, responses, dispatcher,
                    new PlayerBusinessCommandGateway(dispatcher, rpc, responses, timeout));
        }

        private static Fixture createRemoteOwner(ServiceId local, ServiceId remote, RpcGateway rpc) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            ActorRef remoteRef = actors.actor("remote-player-10001");
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            directory.claim(AgentIdentity.player(10001L), new AgentLocation(remote, remoteRef));
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, rpc);
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    ),
                    new InMemoryPlayerCommandAuditLog(),
                    ignored -> 1,
                    CLOCK
            );
            dispatcher.handle(PlayerBusinessOperations.ACTIVITY_PROGRESS, (context, command) -> {
            });
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            return new Fixture(executor, responses, dispatcher,
                    new PlayerBusinessCommandGateway(dispatcher, rpc, responses,
                            PlayerBusinessCommandGateway.DEFAULT_RESPONSE_TIMEOUT));
        }
    }

    private static ActivityService activityService() {
        ActivityCatalog activities = new ActivityCatalog();
        activities.register(new ActivityDefinition(
                "kill-3",
                ActivityType.COUNTER,
                3,
                Reward.of(new ItemStack("gem", 5))
        ));
        return new ActivityService(activities, bagService());
    }

    private static GrowthService growthService() {
        return new GrowthService(bagService(), "exp_potion", 60, 100);
    }

    private static BagService bagService() {
        ItemCatalog items = new ItemCatalog();
        items.register(new ItemDefinition("gem", "currency", 999999));
        items.register(new ItemDefinition("exp_potion", "growth", 999));
        return new BagService(items);
    }

    private static final class RecordingCallback implements RpcCallback<PlayerBusinessResponse> {
        final AtomicReference<PlayerBusinessResponse> response = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(PlayerBusinessResponse response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }

        int queued() {
            return commands.size();
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

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
