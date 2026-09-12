package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.profile.ProfileInterestControl;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.ScenePlayerInterestStats;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RoutedChatServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void worldChatRoutesPlayersToStableShardActors() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(2));
        AtomicReference<ChatJoinResult> first = new AtomicReference<>();
        AtomicReference<ChatJoinResult> second = new AtomicReference<>();

        fixture.chat.joinWorld(new WorldChatJoinRequest("r1", 10001L, 0), first::set);
        fixture.chat.joinWorld(new WorldChatJoinRequest("r1", 10002L, 0), second::set);
        fixture.executor.runAll();

        String firstChannel = fixture.chat.worldChannelId("r1", 10001L);
        String secondChannel = fixture.chat.worldChannelId("r1", 10002L);
        assertNotEquals(firstChannel, secondChannel);
        assertEquals(firstChannel, first.get().channelId());
        assertEquals(secondChannel, second.get().channelId());
        assertEquals(2, fixture.channels.stats().activeChannels());
    }

    @Test
    void allianceChatKeepsAllMembersInAllianceActor() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(4));
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.chat.joinAlliance(new AllianceChatJoinRequest(900L, 10001L), ignored -> {
        });
        fixture.chat.joinAlliance(new AllianceChatJoinRequest(900L, 10002L), ignored -> {
        });
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10002L, "alliance hello", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT, sent.get().status());
        assertEquals(ChatChannelIds.alliance(900L), sent.get().delivery().orElseThrow().channelId());
        assertEquals(2, fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().members().size());
        assertEquals(new ScenePlayerInterestStats(2, 2, 2, 2, 0, 0, 0, 0), fixture.interests.interestStats());
    }

    @Test
    void directChatUsesDedicatedSessionActorWithoutChannelJoin() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(4));
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.chat.sendDirect(new DirectChatSendRequest(10002L, 10001L, "private", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT, sent.get().status());
        assertEquals(ChatChannelIds.direct(10001L, 10002L), sent.get().delivery().orElseThrow().channelId());
        assertEquals(1, fixture.directSessions.activeSessions());
        assertEquals(0, fixture.channels.stats().activeChannels());
        assertEquals(List.of(), fixture.profileInterests.watched);
    }

    @Test
    void channelHistoryIsBoundedPerActor() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(4, 2));

        fixture.chat.joinAlliance(new AllianceChatJoinRequest(900L, 10001L), ignored -> {
        });
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10001L, "one", 0), ignored -> {
        });
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10001L, "two", 0), ignored -> {
        });
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10001L, "three", 0), ignored -> {
        });
        fixture.executor.runAll();

        ChatChannelSnapshot snapshot = fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow();
        assertEquals(List.of("two", "three"), snapshot.history().stream().map(ChatDelivery::text).toList());
        assertEquals(2, fixture.chat.stats().retainedMessages());
        assertEquals(1, fixture.chat.stats().droppedHistoryMessages());
    }

    @Test
    void channelSendFansOutToJoinedMembers() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(4, 10));

        fixture.chat.joinAlliance(new AllianceChatJoinRequest(900L, 10001L), ignored -> {
        });
        fixture.chat.joinAlliance(new AllianceChatJoinRequest(900L, 10002L), ignored -> {
        });
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10001L, "hello", 0), ignored -> {
        });
        fixture.executor.runAll();

        assertEquals(List.of("hello"), fixture.deliverySink.pending(10001L).stream().map(ChatDelivery::text).toList());
        assertEquals(List.of("hello"), fixture.deliverySink.pending(10002L).stream().map(ChatDelivery::text).toList());
        assertEquals(2, fixture.chat.stats().acceptedDeliveryRecipients());
    }

    @Test
    void directSendFansOutOnlyToSessionParticipants() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(4, 10));

        fixture.chat.sendDirect(new DirectChatSendRequest(10001L, 10002L, "private", 0), ignored -> {
        });
        fixture.executor.runAll();

        assertEquals(List.of("private"), fixture.deliverySink.pending(10001L).stream().map(ChatDelivery::text).toList());
        assertEquals(List.of("private"), fixture.deliverySink.pending(10002L).stream().map(ChatDelivery::text).toList());
        assertEquals(List.of(), fixture.deliverySink.pending(10003L));
        assertEquals(2, fixture.chat.stats().acceptedDeliveryRecipients());
    }

    @Test
    void slowConsumerQueueDropsOldestDelivery() {
        BoundedInMemoryChatDeliverySink sink = new BoundedInMemoryChatDeliverySink(
                2,
                ChatDeliveryOverflowStrategy.DROP_OLDEST
        );

        sink.deliver(envelope("world", 10001L, "one"));
        sink.deliver(envelope("world", 10001L, "two"));
        sink.deliver(envelope("world", 10001L, "three"));

        assertEquals(List.of("two", "three"), sink.pending(10001L).stream().map(ChatDelivery::text).toList());
        assertEquals(new ChatDeliveryResult(3, 1, 0), sink.stats());
    }

    @Test
    void accessControlRejectsMutedPlayerAndBlockedChannel() {
        Fixture fixture = Fixture.create(new ChatRouteConfig(4, 10));
        AtomicReference<ChatSendResult> muted = new AtomicReference<>();
        AtomicReference<ChatSendResult> blocked = new AtomicReference<>();

        fixture.accessControl.mute(10001L);
        fixture.chat.joinAlliance(new AllianceChatJoinRequest(900L, 10001L), ignored -> {
        });
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10001L, "muted", 0), muted::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.MUTED, muted.get().status());
        assertEquals(0, fixture.channels.channel(ChatChannelIds.alliance(900L)).snapshotNow().history().size());

        fixture.accessControl.unmute(10001L);
        fixture.accessControl.blockChannel(ChatChannelIds.alliance(900L));
        fixture.chat.sendAlliance(new AllianceChatSendRequest(900L, 10001L, "blocked", 0), blocked::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.BLOCKED, blocked.get().status());
        assertEquals(1, fixture.chat.stats().mutedRejects());
        assertEquals(1, fixture.chat.stats().blockedRejects());
        assertEquals(0, fixture.chat.stats().retainedMessages());
    }

    private record Fixture(
            RecordingExecutor executor,
            RecordingProfileInterests profileInterests,
            ChatAccessControl accessControl,
            BoundedInMemoryChatDeliverySink deliverySink,
            ScenePlayerInterestCoordinator interests,
            ChatChannelManager channels,
            DirectChatSessionManager directSessions,
            RoutedChatService chat
    ) {
        private static Fixture create(ChatRouteConfig config) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            RecordingProfileInterests profileInterests = new RecordingProfileInterests();
            ChatAccessControl accessControl = new ChatAccessControl();
            BoundedInMemoryChatDeliverySink deliverySink = new BoundedInMemoryChatDeliverySink(
                    config.maxPendingDeliveriesPerRecipient(),
                    config.deliveryOverflowStrategy()
            );
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    new SceneProfileAwarenessAgent(messages, actors.actor("chat-profile-awareness"), profileInterests),
                    new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness")),
                    new ScenePlayerDomainEventAgent(messages, actors.actor("chat-domain-awareness")),
                    new SceneAllianceAwarenessAgent(messages, actors.actor("chat-alliance-awareness"))
            );
            ChatMessagePolicy basePolicy = request ->
                    ChatMessageDecision.sent("player-" + request.senderId(), request.text().trim());
            ChatMessagePolicy policy = new AccessControlledChatMessagePolicy(accessControl, basePolicy);
            ChatChannelManager channels = new ChatChannelManager(
                    actors,
                    messages,
                    interests,
                    policy,
                    deliverySink,
                    CLOCK,
                    config.maxHistoryMessages()
            );
            DirectChatSessionManager directSessions = new DirectChatSessionManager(
                    actors,
                    messages,
                    policy,
                    deliverySink,
                    CLOCK,
                    config.maxHistoryMessages()
            );
            return new Fixture(
                    executor,
                    profileInterests,
                    accessControl,
                    deliverySink,
                    interests,
                    channels,
                    directSessions,
                    new RoutedChatService(channels, directSessions, config, accessControl)
            );
        }
    }

    private static ChatDeliveryEnvelope envelope(String channelId, long recipient, String text) {
        ChatDelivery delivery = new ChatDelivery(channelId, 10001L, "player-10001", text, 1, CLOCK.instant());
        return new ChatDeliveryEnvelope(channelId, java.util.Set.of(recipient), delivery);
    }

    private static final class RecordingProfileInterests implements ProfileInterestControl {
        private final List<Long> watched = new ArrayList<>();

        @Override
        public void watch(long playerId) {
            watched.add(playerId);
        }

        @Override
        public void unwatch(long playerId) {
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

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

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
