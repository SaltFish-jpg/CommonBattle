package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.backpressure.ActorMailboxPressureAdmissionController;
import com.commonbattle.actor.backpressure.ActorMailboxPressurePolicy;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
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
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatChannelEndpointTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void remoteJoinAndSendOnlyCompleteAfterChatActorMailboxRuns() {
        try (Fixture fixture = Fixture.create()) {
            RecordingCallback<ChatJoinResult> joined = new RecordingCallback<>();
            RecordingCallback<ChatSendResult> sent = new RecordingCallback<>();

            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.JOIN_CHANNEL,
                    new ChatJoinRequest("world", 10001L, 0),
                    ChatJoinResult.class
            ), joined);

            assertNull(joined.response.get());
            fixture.chatExecutor.runAll();
            assertEquals(ChatJoinStatus.JOINED, joined.response.get().status());

            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.SEND_CHANNEL,
                    new ChatSendRequest("world", 10001L, "hello", 0),
                    ChatSendResult.class
            ), sent);

            assertNull(sent.response.get());
            fixture.chatExecutor.runAll();
            assertEquals(ChatSendStatus.SENT, sent.response.get().status());
            assertEquals("player-10001", sent.response.get().delivery().orElseThrow().senderName());
        }
    }

    @Test
    void remoteWorldAllianceAndDirectRequestsRouteToBusinessActors() {
        try (Fixture fixture = Fixture.create()) {
            RecordingCallback<ChatJoinResult> worldJoin = new RecordingCallback<>();
            RecordingCallback<ChatJoinResult> allianceJoin = new RecordingCallback<>();
            RecordingCallback<ChatSendResult> allianceSend = new RecordingCallback<>();
            RecordingCallback<ChatSendResult> directSend = new RecordingCallback<>();

            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.JOIN_WORLD,
                    new WorldChatJoinRequest("r1", 10001L, 0),
                    ChatJoinResult.class
            ), worldJoin);
            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.JOIN_ALLIANCE,
                    new AllianceChatJoinRequest(900L, 10001L),
                    ChatJoinResult.class
            ), allianceJoin);
            fixture.chatExecutor.runAll();

            assertEquals(ChatJoinStatus.JOINED, worldJoin.response.get().status());
            assertEquals(fixture.routedChat().worldChannelId("r1", 10001L), worldJoin.response.get().channelId());
            assertEquals(ChatJoinStatus.JOINED, allianceJoin.response.get().status());
            assertEquals(ChatChannelIds.alliance(900L), allianceJoin.response.get().channelId());

            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.SEND_ALLIANCE,
                    new AllianceChatSendRequest(900L, 10001L, "alliance", 0),
                    ChatSendResult.class
            ), allianceSend);
            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.SEND_DIRECT,
                    new DirectChatSendRequest(10001L, 10002L, "direct", 0),
                    ChatSendResult.class
            ), directSend);

            assertNull(allianceSend.response.get());
            assertNull(directSend.response.get());
            fixture.chatExecutor.runAll();

            assertEquals(ChatSendStatus.SENT, allianceSend.response.get().status());
            assertEquals(ChatChannelIds.alliance(900L), allianceSend.response.get().delivery().orElseThrow().channelId());
            assertEquals(ChatSendStatus.SENT, directSend.response.get().status());
            assertEquals(ChatChannelIds.direct(10001L, 10002L), directSend.response.get().delivery().orElseThrow().channelId());
        }
    }

    @Test
    void remoteChannelBackpressureReturnsStructuredRpcFailure() {
        try (Fixture fixture = Fixture.create(new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50)))) {
            fixture.actors.send(fixture.actors.actor(ChatActorIds.channelActorId("world")), ignored -> {
            });
            RecordingCallback<ChatJoinResult> joined = new RecordingCallback<>();

            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.JOIN_CHANNEL,
                    new ChatJoinRequest("world", 10001L, 0),
                    ChatJoinResult.class
            ), joined);

            RpcStructuredException failure = assertInstanceOf(RpcStructuredException.class, joined.failure.get());
            assertEquals("mailbox_pressure:target", failure.code());
            assertNull(joined.response.get());
        }
    }

    @Test
    void remoteDirectBackpressureReturnsStructuredRpcFailure() {
        try (Fixture fixture = Fixture.create(new ActorMailboxPressurePolicy(true, 1, 0, Duration.ofMillis(50)))) {
            fixture.actors.send(fixture.actors.actor(ChatActorIds.directActorId(10001L, 10002L)), ignored -> {
            });
            RecordingCallback<ChatSendResult> sent = new RecordingCallback<>();

            fixture.gameGateway.call(new RpcRequest<>(
                    ServiceKind.CHAT.name(),
                    ChatOperations.SEND_DIRECT,
                    new DirectChatSendRequest(10001L, 10002L, "direct", 0),
                    ChatSendResult.class
            ), sent);

            RpcStructuredException failure = assertInstanceOf(RpcStructuredException.class, sent.failure.get());
            assertEquals("mailbox_pressure:target", failure.code());
            assertNull(sent.response.get());
        }
    }

    private record Fixture(
            LocalClusterTransport transport,
            RecordingExecutor chatExecutor,
            ActorSystem actors,
            ClusterRpcGateway gameGateway,
            ClusterRpcGateway chatGateway,
            RoutedChatService routedChat
    ) implements AutoCloseable {
        private static Fixture create() {
            return create(ActorMailboxPressurePolicy.disabled());
        }

        private static Fixture create(ActorMailboxPressurePolicy pressurePolicy) {
            InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
            LocalClusterTransport transport = new LocalClusterTransport();
            ServiceDescriptor game = descriptor(ServiceKind.GAME, "game-1", 9101, Set.of());
            ServiceDescriptor chat = descriptor(ServiceKind.CHAT, "chat-1", 9106, Set.of(
                    ChatOperations.JOIN_CHANNEL,
                    ChatOperations.LEAVE_CHANNEL,
                    ChatOperations.SEND_CHANNEL,
                    ChatOperations.JOIN_WORLD,
                    ChatOperations.LEAVE_WORLD,
                    ChatOperations.SEND_WORLD,
                    ChatOperations.JOIN_ALLIANCE,
                    ChatOperations.LEAVE_ALLIANCE,
                    ChatOperations.SEND_ALLIANCE,
                    ChatOperations.SEND_DIRECT
            ));
            registry.register(game);
            registry.register(chat);
            ClusterTopology topology = new ClusterTopology()
                    .allow(ServiceKind.GAME, ServiceKind.CHAT)
                    .allow(ServiceKind.CHAT, ServiceKind.GAME);
            ClusterRpcGateway gameGateway = new ClusterRpcGateway(game, directory(registry), topology, transport);
            ClusterRpcGateway chatGateway = new ClusterRpcGateway(chat, directory(registry), topology, transport);
            RecordingExecutor chatExecutor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(chatExecutor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, chatGateway);
            ActorMailboxPressureAdmissionController pressure = new ActorMailboxPressureAdmissionController(
                    (target, operation) -> AdmissionDecision.accept(),
                    actors,
                    pressurePolicy,
                    ChatActorIds::actorIdOf
            );
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    new SceneProfileAwarenessAgent(
                            messages,
                            actors.actor("chat-profile-awareness"),
                            ProfileInterestControl.noop()
                    ),
                    new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness")),
                    new ScenePlayerDomainEventAgent(messages, actors.actor("chat-domain-awareness")),
                    new SceneAllianceAwarenessAgent(messages, actors.actor("chat-alliance-awareness"))
            );
            ChatMessagePolicy policy = request ->
                    ChatMessageDecision.sent("player-" + request.senderId(), request.text().trim());
            ChatChannelManager manager = new ChatChannelManager(
                    actors,
                    messages,
                    interests,
                    policy,
                    ChatDeliverySink.noop(),
                    CLOCK,
                    ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                    pressure
            );
            DirectChatSessionManager directSessions = new DirectChatSessionManager(
                    actors,
                    messages,
                    policy,
                    ChatDeliverySink.noop(),
                    CLOCK,
                    ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                    pressure
            );
            RoutedChatService routedChat = new RoutedChatService(manager, directSessions, new ChatRouteConfig(4));
            new ChatChannelEndpoint(manager).bind(chatGateway);
            new RoutedChatEndpoint(routedChat).bind(chatGateway);
            return new Fixture(transport, chatExecutor, actors, gameGateway, chatGateway, routedChat);
        }

        @Override
        public void close() {
            gameGateway.close();
            chatGateway.close();
            actors.close();
            transport.close();
        }
    }

    private static ClusterDirectory directory(InMemoryServiceRegistry registry) {
        ClusterDirectory directory = new ClusterDirectory(registry);
        for (ServiceKind kind : ServiceKind.values()) {
            directory.watch(kind);
        }
        return directory;
    }

    private static ServiceDescriptor descriptor(ServiceKind kind, String node, int port, Set<String> operations) {
        return new ServiceDescriptor(
                ServiceId.of(kind, "r1", node),
                new ServiceEndpoint("127.0.0.1", port),
                operations,
                Map.of()
        );
    }

    private static final class RecordingCallback<T> implements RpcCallback<T> {
        private final AtomicReference<T> response = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        public void success(T response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            failure.set(error);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final ArrayList<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        private void runAll() {
            while (!commands.isEmpty()) {
                commands.removeFirst().run();
            }
        }
    }
}
