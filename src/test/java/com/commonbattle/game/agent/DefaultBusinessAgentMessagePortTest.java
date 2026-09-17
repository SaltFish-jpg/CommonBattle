package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.ActorMailboxPressureAdmissionController;
import com.commonbattle.actor.backpressure.ActorMailboxPressurePolicy;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.ClusterTopology;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.network.LocalClusterTransport;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;
import com.commonbattle.cluster.rpc.RpcStructuredException;
import com.commonbattle.game.player.PlayerBusinessCommandEndpoint;
import com.commonbattle.game.player.PlayerBusinessCommandGateway;
import com.commonbattle.game.player.PlayerBusinessResponse;
import com.commonbattle.game.player.PlayerBusinessResponseHub;
import com.commonbattle.game.player.PlayerBusinessResponseStatus;
import com.commonbattle.game.player.PlayerBusinessRpcOperations;
import com.commonbattle.game.session.InMemoryPlayerCommandAuditLog;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.social.AllianceAgent;
import com.commonbattle.game.social.AllianceMemberRequest;
import com.commonbattle.game.social.AllianceSnapshot;
import com.commonbattle.game.social.FriendAgent;
import com.commonbattle.game.social.FriendRelationRequest;
import com.commonbattle.game.social.FriendSnapshot;
import com.commonbattle.game.social.SocialAgentOperationBinder;
import com.commonbattle.game.social.SocialAgentOperations;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultBusinessAgentMessagePortTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String OPERATION = "test.player.echo";

    @Test
    void delegatesLocalTellToActorMailbox() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), rpc)) {
            ActorRef worker = node.actors().actor("worker");
            AtomicInteger value = new AtomicInteger();

            node.business().tellLocal(worker, ignored -> value.incrementAndGet());

            assertEquals(0, value.get());
            node.executor().runAll();
            assertEquals(1, value.get());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void sendsLocalPlayerCommandThroughPlayerMailbox() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), rpc)) {
            RecordingCallback callback = new RecordingCallback();

            node.business().sendPlayerCommand(command(1), callback);

            assertNull(callback.response.get());
            assertEquals(1, node.responses().pendingResponses());
            node.executor().runAll();

            assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
            assertEquals("player-10001", callback.response.get().payload());
            assertEquals(0, node.responses().pendingResponses());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void sendsRemotePlayerCommandThroughRpcAndOwnerMailbox() {
        try (RemoteFixture fixture = RemoteFixture.create()) {
            RecordingCallback callback = new RecordingCallback();

            fixture.caller().business().sendPlayerCommand(command(1), callback);

            assertNull(callback.response.get());
            assertEquals(1, fixture.owner().responses().pendingResponses());
            fixture.owner().executor().runAll();

            assertEquals(PlayerBusinessResponseStatus.SUCCESS, callback.response.get().status());
            assertEquals("player-10001", callback.response.get().payload());
            assertEquals(0, fixture.owner().responses().pendingResponses());
        }
    }

    @Test
    void requestsLocalBusinessAgentThroughOwnerMailboxAndRequesterCallbackMailbox() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), rpc)) {
            node.lifecycles().activate(AgentIdentity.alliance(100), "alliance-100");
            node.executor().runAll();
            ActorRef requester = node.actors().actor("requester");
            AtomicReference<String> response = new AtomicReference<>();
            AtomicReference<String> callbackActor = new AtomicReference<>();
            node.handlers().handle("alliance.memberCount", (context, request) -> context.self().id() + ":32");

            node.business().requestAgent(
                    requester,
                    AgentIdentity.alliance(100),
                    "alliance.memberCount",
                    null,
                    String.class,
                    new com.commonbattle.actor.message.LocalAskCallback<>() {
                        @Override
                        public void success(com.commonbattle.actor.ActorContext context, String result) {
                            callbackActor.set(context.self().id());
                            response.set(result);
                        }

                        @Override
                        public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                            throw new AssertionError(error);
                        }
                    }
            );

            assertNull(response.get());
            node.executor().runAll();

            assertEquals("alliance-100:32", response.get());
            assertEquals("requester", callbackActor.get());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void localBusinessAgentRequestIsRejectedBeforeHotOwnerMailbox() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(
                ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                rpc,
                ActorMailboxPressurePolicy.disabled()
        )) {
            node.lifecycles().activate(AgentIdentity.alliance(100), "alliance-100");
            node.executor().runAll();
            node.actors().send(new ActorRef("alliance-100"), ignored -> {
            });
            DefaultBusinessAgentMessagePort pressured = node.businessWithPolicy(
                    new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50)));
            ActorRef requester = node.actors().actor("requester");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger handled = new AtomicInteger();
            node.handlers().handle("alliance.memberCount", (context, request) -> {
                handled.incrementAndGet();
                return "unexpected";
            });

            pressured.requestAgent(
                    requester,
                    AgentIdentity.alliance(100),
                    "alliance.memberCount",
                    null,
                    String.class,
                    failureOnly(failure)
            );
            node.executor().runAll();

            assertEquals(0, handled.get());
            assertEquals("mailbox_pressure:target", ((BusinessAgentRequestException) failure.get()).reason());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void requestsRemoteBusinessAgentThroughRpcOwnerMailboxAndRequesterCallbackMailbox() {
        try (RemoteFixture fixture = RemoteFixture.create()) {
            AgentLocation allianceOwner = fixture.owner().lifecycles()
                    .activate(AgentIdentity.alliance(100), "alliance-100");
            fixture.owner().executor().runAll();
            fixture.caller().directory().claim(AgentIdentity.alliance(100), allianceOwner);
            ActorRef requester = fixture.caller().actors().actor("requester");
            AtomicReference<String> response = new AtomicReference<>();
            AtomicReference<String> callbackActor = new AtomicReference<>();
            fixture.owner().handlers().handle("alliance.owner", (context, request) -> context.self().id());

            fixture.caller().business().requestAgent(
                    requester,
                    AgentIdentity.alliance(100),
                    "alliance.owner",
                    null,
                    String.class,
                    new com.commonbattle.actor.message.LocalAskCallback<>() {
                        @Override
                        public void success(com.commonbattle.actor.ActorContext context, String result) {
                            callbackActor.set(context.self().id());
                            response.set(result);
                        }

                        @Override
                        public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                            throw new AssertionError(error);
                        }
                    }
            );

            assertNull(response.get());
            fixture.owner().executor().runAll();
            assertNull(response.get());
            fixture.caller().executor().runAll();

            assertEquals("alliance-100", response.get());
            assertEquals("requester", callbackActor.get());
        }
    }

    @Test
    void remoteBusinessAgentRpcIsRejectedBeforeHotOwnerMailbox() {
        try (RemoteFixture fixture = RemoteFixture.create(owner -> new ActorMailboxPressureAdmissionController(
                (target, operation) -> AdmissionDecision.accept(),
                owner.actors(),
                new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50))
        ))) {
            AgentLocation allianceOwner = fixture.owner().lifecycles()
                    .activate(AgentIdentity.alliance(100), "alliance-100");
            fixture.owner().executor().runAll();
            fixture.owner().actors().send(allianceOwner.actorRef(), ignored -> {
            });
            fixture.caller().directory().claim(AgentIdentity.alliance(100), allianceOwner);
            ActorRef requester = fixture.caller().actors().actor("requester");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger handled = new AtomicInteger();
            fixture.owner().handlers().handle("alliance.owner", (context, request) -> {
                handled.incrementAndGet();
                return context.self().id();
            });

            fixture.caller().business().requestAgent(
                    requester,
                    AgentIdentity.alliance(100),
                    "alliance.owner",
                    null,
                    String.class,
                    failureOnly(failure)
            );
            fixture.owner().executor().runAll();
            fixture.caller().executor().runAll();

            assertEquals(0, handled.get());
            RpcStructuredException rejected = assertInstanceOf(RpcStructuredException.class, failure.get());
            assertEquals("mailbox_pressure:target", rejected.code());
            assertEquals("mailbox_pressure:target", rejected.getMessage());
            assertEquals(50, rejected.retryAfter().toMillis());
        }
    }

    @Test
    void missingBusinessAgentFailureIsDeliveredToRequesterMailbox() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), rpc)) {
            ActorRef requester = node.actors().actor("requester");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<String> callbackActor = new AtomicReference<>();

            node.business().requestAgent(
                    requester,
                    AgentIdentity.alliance(404),
                    "alliance.memberCount",
                    null,
                    String.class,
                    new com.commonbattle.actor.message.LocalAskCallback<>() {
                        @Override
                        public void success(com.commonbattle.actor.ActorContext context, String result) {
                            throw new AssertionError(result);
                        }

                        @Override
                        public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                            callbackActor.set(context.self().id());
                            failure.set(error);
                        }
                    }
            );

            assertNull(failure.get());
            node.executor().runAll();

            assertEquals("requester", callbackActor.get());
            assertEquals("agent_missing", ((BusinessAgentRequestException) failure.get()).reason());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void socialAgentOperationsMutateFriendOwnerAndReturnSnapshot() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), rpc)) {
            AgentLocation friendLocation = node.lifecycles().activate(AgentIdentity.friend(10001L), "friend-10001");
            node.executor().runAll();
            FriendAgent friends = new FriendAgent(
                    new DefaultAgentMessagePort(node.actors(), rpc),
                    friendLocation.actorRef(),
                    10001L,
                    ignored -> {
                    }
            );
            SocialAgentOperationBinder.registerFriends(node.handlers(), ignored -> friends);
            ActorRef requester = node.actors().actor("requester");
            AtomicReference<FriendSnapshot> response = new AtomicReference<>();

            node.business().requestAgent(
                    requester,
                    AgentIdentity.friend(10001L),
                    SocialAgentOperations.FRIEND_ADD,
                    new FriendRelationRequest(20002L),
                    FriendSnapshot.class,
                    successOnly(response)
            );
            node.executor().runAll();

            assertEquals(1, response.get().revision());
            assertEquals(Set.of(20002L), response.get().friends());
            assertEquals(0, rpc.calls.get());
        }
    }

    @Test
    void socialAgentOperationInvalidPayloadFailsOnRequesterMailbox() {
        CountingRpcGateway rpc = new CountingRpcGateway();
        try (PlayerNode node = PlayerNode.localOwner(ServiceId.of(ServiceKind.GAME, "r1", "game-1"), rpc)) {
            AgentLocation friendLocation = node.lifecycles().activate(AgentIdentity.friend(10001L), "friend-10001");
            node.executor().runAll();
            FriendAgent friends = new FriendAgent(
                    new DefaultAgentMessagePort(node.actors(), rpc),
                    friendLocation.actorRef(),
                    10001L,
                    ignored -> {
                    }
            );
            SocialAgentOperationBinder.registerFriends(node.handlers(), ignored -> friends);
            ActorRef requester = node.actors().actor("requester");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<String> callbackActor = new AtomicReference<>();

            node.business().requestAgent(
                    requester,
                    AgentIdentity.friend(10001L),
                    SocialAgentOperations.FRIEND_ADD,
                    "bad-payload",
                    FriendSnapshot.class,
                    new com.commonbattle.actor.message.LocalAskCallback<>() {
                        @Override
                        public void success(com.commonbattle.actor.ActorContext context, FriendSnapshot result) {
                            throw new AssertionError(result);
                        }

                        @Override
                        public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                            callbackActor.set(context.self().id());
                            failure.set(error);
                        }
                    }
            );
            node.executor().runAll();

            assertEquals("requester", callbackActor.get());
            assertEquals("invalid_payload", ((BusinessAgentRequestException) failure.get()).reason());
        }
    }

    @Test
    void remoteSocialAgentOperationMutatesAllianceOwnerAndReturnsSnapshot() {
        try (RemoteFixture fixture = RemoteFixture.create()) {
            AgentLocation allianceLocation = fixture.owner().lifecycles()
                    .activate(AgentIdentity.alliance(100L), "alliance-100");
            fixture.owner().executor().runAll();
            fixture.caller().directory().claim(AgentIdentity.alliance(100L), allianceLocation);
            AllianceAgent alliance = new AllianceAgent(
                    new DefaultAgentMessagePort(fixture.owner().actors(), fixture.ownerGateway()),
                    allianceLocation.actorRef(),
                    100L,
                    ignored -> {
                    }
            );
            SocialAgentOperationBinder.registerAlliances(fixture.owner().handlers(), ignored -> alliance);
            ActorRef requester = fixture.caller().actors().actor("requester");
            AtomicReference<AllianceSnapshot> response = new AtomicReference<>();

            fixture.caller().business().requestAgent(
                    requester,
                    AgentIdentity.alliance(100L),
                    SocialAgentOperations.ALLIANCE_JOIN,
                    new AllianceMemberRequest(10001L),
                    AllianceSnapshot.class,
                    successOnly(response)
            );
            fixture.owner().executor().runAll();
            assertNull(response.get());
            fixture.caller().executor().runAll();

            assertEquals(1, response.get().revision());
            assertEquals(Set.of(10001L), response.get().members());
        }
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(10001L, "session-1", 1, sequence, OPERATION, "payload");
    }

    private static <T> com.commonbattle.actor.message.LocalAskCallback<T> successOnly(AtomicReference<T> response) {
        return new com.commonbattle.actor.message.LocalAskCallback<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, T result) {
                response.set(result);
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                throw new AssertionError(error);
            }
        };
    }

    private static <T> com.commonbattle.actor.message.LocalAskCallback<T> failureOnly(AtomicReference<Throwable> failure) {
        return new com.commonbattle.actor.message.LocalAskCallback<>() {
            @Override
            public void success(com.commonbattle.actor.ActorContext context, T result) {
                throw new AssertionError(result);
            }

            @Override
            public void failure(com.commonbattle.actor.ActorContext context, Throwable error) {
                failure.set(error);
            }
        };
    }

    private static ServiceDescriptor descriptor(String node, int port) {
        return new ServiceDescriptor(
                ServiceId.of(ServiceKind.GAME, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                Set.of(PlayerBusinessRpcOperations.DISPATCH),
                Map.of()
        );
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private record PlayerNode(
            RecordingExecutor executor,
            ActorSystem actors,
            InMemoryAgentDirectory directory,
            AgentLifecycleManager lifecycles,
            LifecycleAwareAgentRouter router,
            BusinessAgentHandlerRegistry handlers,
            PlayerBusinessResponseHub responses,
            PlayerBusinessCommandGateway gateway,
            RpcGateway rpc,
            DefaultBusinessAgentMessagePort business
    ) implements AutoCloseable {
        static PlayerNode localOwner(ServiceId local, RpcGateway rpc) {
            return localOwner(local, rpc, ActorMailboxPressurePolicy.disabled());
        }

        static PlayerNode localOwner(ServiceId local, RpcGateway rpc, ActorMailboxPressurePolicy businessPolicy) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            lifecycles.activate(AgentIdentity.player(10001L), "player-10001");
            executor.runAll();
            return create(rpc, actors, executor, directory, lifecycles, businessPolicy);
        }

        static PlayerNode remoteCaller(ServiceId local, RpcGateway rpc, AgentLocation ownerLocation) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            directory.claim(AgentIdentity.player(10001L), ownerLocation);
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(local, actors, directory, CLOCK);
            return create(rpc, actors, executor, directory, lifecycles, ActorMailboxPressurePolicy.disabled());
        }

        private static PlayerNode create(
                RpcGateway rpc,
                ActorSystem actors,
                RecordingExecutor executor,
                InMemoryAgentDirectory directory,
                AgentLifecycleManager lifecycles,
                ActorMailboxPressurePolicy businessPolicy
        ) {
            DefaultAgentMessagePort baseMessages = new DefaultAgentMessagePort(actors, rpc);
            LifecycleAwareAgentRouter router = new LifecycleAwareAgentRouter(lifecycles, baseMessages);
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            sessions.bind(10001L, "session-1");
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            router
                    ),
                    new InMemoryPlayerCommandAuditLog(),
                    ignored -> 1,
                    CLOCK
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            dispatcher.handle(OPERATION, (context, command) ->
                    responses.completed(command, PlayerBusinessResponse.success(command, context.self().id())));
            PlayerBusinessCommandGateway gateway = new PlayerBusinessCommandGateway(dispatcher, rpc, responses);
            BusinessAgentHandlerRegistry handlers = new BusinessAgentHandlerRegistry();
            DefaultBusinessAgentMessagePort business = new DefaultBusinessAgentMessagePort(
                    baseMessages,
                    gateway,
                    router,
                    handlers,
                    businessAdmissions(actors, businessPolicy)
            );
            return new PlayerNode(executor, actors, directory, lifecycles, router, handlers, responses, gateway, rpc, business);
        }

        private DefaultBusinessAgentMessagePort businessWithPolicy(ActorMailboxPressurePolicy policy) {
            return new DefaultBusinessAgentMessagePort(
                    new DefaultAgentMessagePort(actors, rpc),
                    gateway,
                    router,
                    handlers,
                    businessAdmissions(actors, policy)
            );
        }

        private static InboundAdmissionController businessAdmissions(
                ActorSystem actors,
                ActorMailboxPressurePolicy policy
        ) {
            return new ActorMailboxPressureAdmissionController(
                    (target, operation) -> AdmissionDecision.accept(),
                    actors,
                    policy
            );
        }

        @Override
        public void close() {
            gateway.close();
            actors.close();
        }
    }

    private record RemoteFixture(
            LocalClusterTransport transport,
            ClusterRpcGateway callerGateway,
            ClusterRpcGateway ownerGateway,
            PlayerNode caller,
            PlayerNode owner
    ) implements AutoCloseable {
        static RemoteFixture create() {
            return create(owner -> (target, operation) -> AdmissionDecision.accept());
        }

        static RemoteFixture create(java.util.function.Function<PlayerNode, InboundAdmissionController> ownerAdmissions) {
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            LocalClusterTransport transport = new LocalClusterTransport();
            ClusterTopology topology = new ClusterTopology().allow(ServiceKind.GAME, ServiceKind.GAME);
            ServiceDescriptor callerDescriptor = descriptor("game-1", 9101);
            ServiceDescriptor ownerDescriptor = descriptor("game-2", 9102);
            registry.register(callerDescriptor);
            registry.register(ownerDescriptor);
            ClusterRpcGateway callerGateway = new ClusterRpcGateway(callerDescriptor, directory(registry), topology, transport);
            ClusterRpcGateway ownerGateway = new ClusterRpcGateway(ownerDescriptor, directory(registry), topology, transport);
            PlayerNode owner = PlayerNode.localOwner(ownerDescriptor.id(), ownerGateway);
            new PlayerBusinessCommandEndpoint(owner.gateway()).bind(ownerGateway);
            new BusinessAgentRpcEndpoint(owner.router(), owner.handlers(), ownerAdmissions.apply(owner)).bind(ownerGateway);
            PlayerNode caller = PlayerNode.remoteCaller(
                    callerDescriptor.id(),
                    callerGateway,
                    new AgentLocation(ownerDescriptor.id(), new ActorRef("player-10001"))
            );
            return new RemoteFixture(transport, callerGateway, ownerGateway, caller, owner);
        }

        @Override
        public void close() {
            caller.close();
            owner.close();
            callerGateway.close();
            ownerGateway.close();
            transport.close();
        }
    }

    private static final class RecordingCallback implements RpcCallback<PlayerBusinessResponse> {
        private final AtomicReference<PlayerBusinessResponse> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(PlayerBusinessResponse response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
        }
    }

    private static final class CountingRpcGateway implements RpcGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            calls.incrementAndGet();
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
    }
}
