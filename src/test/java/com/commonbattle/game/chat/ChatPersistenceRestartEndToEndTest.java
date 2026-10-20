package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import com.commonbattle.persistence.InMemoryAtomicBytesStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPersistenceRestartEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void directSessionRecoversHistoryAndRevisionAfterProcessRestart() throws InterruptedException {
        SerializedChatStateStore durableStore = new SerializedChatStateStore(
                new InMemoryAtomicBytesStore(), new ProtoChatStateSerializer()
        );
        ChatSendResult first;
        try (ActorSystem actors = new ActorSystem(1);
             ExecutorChatStateStore asyncStore = new ExecutorChatStateStore(
                     durableStore, 1, 32, "chat-state-first"
             )) {
            DirectChatSessionManager manager = manager(actors, asyncStore);
            first = send(manager, "first");
            assertEquals(1, first.delivery().orElseThrow().revision());
        }

        try (ActorSystem actors = new ActorSystem(1);
             ExecutorChatStateStore asyncStore = new ExecutorChatStateStore(
                     durableStore, 1, 32, "chat-state-second"
             )) {
            DirectChatSessionManager manager = manager(actors, asyncStore);
            ChatSendResult second = send(manager, "second");

            assertEquals(2, second.delivery().orElseThrow().revision());
            assertEquals(2, manager.session(10001L, 10002L).snapshotNow().history().size());
            assertEquals("first", manager.session(10001L, 10002L).snapshotNow().history().getFirst().text());
            assertEquals("second", manager.session(10001L, 10002L).snapshotNow().history().getLast().text());
        }
    }

    @Test
    void channelRecoversMemberAllianceContextAndRebuildsInterestsAfterRestart() throws InterruptedException {
        SerializedChatStateStore durableStore = new SerializedChatStateStore(
                new InMemoryAtomicBytesStore(), new ProtoChatStateSerializer()
        );
        try (ActorSystem actors = new ActorSystem(1);
             ExecutorChatStateStore asyncStore = new ExecutorChatStateStore(
                     durableStore, 1, 32, "chat-channel-state-first"
             )) {
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            ChatChannelManager manager = channelManager(actors, messages, interests(actors, messages), asyncStore);
            AtomicReference<ChatJoinResult> result = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            manager.join(new ChatJoinRequest("world:1", 10001L, 900L), response -> {
                result.set(response);
                completed.countDown();
            });
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(ChatJoinStatus.JOINED, result.get().status());
        }

        try (ActorSystem actors = new ActorSystem(1);
             ExecutorChatStateStore asyncStore = new ExecutorChatStateStore(
                     durableStore, 1, 32, "chat-channel-state-second"
             )) {
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            ScenePlayerInterestCoordinator interests = interests(actors, messages);
            ChatChannelManager manager = channelManager(actors, messages, interests, asyncStore);
            AtomicReference<ChatChannelSnapshot> restored = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);

            manager.channel("world:1").snapshot(snapshot -> {
                restored.set(snapshot);
                completed.countDown();
            });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(10001L), restored.get().members());
            assertEquals(java.util.Map.of(10001L, 900L), restored.get().memberAllianceIds());
            assertEquals(1, interests.interestStats().players());
            assertEquals(1, interests.interestStats().allianceReferences());
        }
    }

    private static ChatSendResult send(DirectChatSessionManager manager, String text) throws InterruptedException {
        AtomicReference<ChatSendResult> result = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        manager.send(new DirectChatSendRequest(10001L, 10002L, text, 0), response -> {
            result.set(response);
            completed.countDown();
        });
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(ChatSendStatus.SENT, result.get().status());
        return result.get();
    }

    private static DirectChatSessionManager manager(ActorSystem actors, AsyncChatStateStore store) {
        return new DirectChatSessionManager(
                actors,
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                DirectChatAccessPolicy.allowAll(),
                null,
                request -> ChatMessageDecision.sent("player", request.text().trim()),
                ChatDeliverySink.noop(),
                CLOCK,
                20,
                (target, operation) -> AdmissionDecision.accept(),
                null,
                store,
                32
        );
    }

    private static ChatChannelManager channelManager(
            ActorSystem actors,
            DefaultAgentMessagePort messages,
            ScenePlayerInterestCoordinator interests,
            AsyncChatStateStore store
    ) {
        return new ChatChannelManager(
                actors,
                messages,
                interests,
                ChatChannelAccessPolicy.allowAll(),
                null,
                request -> ChatMessageDecision.sent("player", request.text().trim()),
                null,
                ChatDeliverySink.noop(),
                CLOCK,
                20,
                (target, operation) -> AdmissionDecision.accept(),
                null,
                store,
                32
        );
    }

    private static ScenePlayerInterestCoordinator interests(
            ActorSystem actors,
            DefaultAgentMessagePort messages
    ) {
        return new ScenePlayerInterestCoordinator(
                new SceneProfileAwarenessAgent(messages, actors.actor("chat-profile-awareness")),
                new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness")),
                new ScenePlayerDomainEventAgent(messages, actors.actor("chat-domain-awareness")),
                new SceneAllianceAwarenessAgent(messages, actors.actor("chat-alliance-awareness"))
        );
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
