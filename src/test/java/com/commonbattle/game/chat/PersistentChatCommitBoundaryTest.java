package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.scene.SceneAllianceAwarenessAgent;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.scene.ScenePlayerDomainEventAgent;
import com.commonbattle.game.scene.ScenePlayerInterestCoordinator;
import com.commonbattle.game.scene.SceneProfileAwarenessAgent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersistentChatCommitBoundaryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void deliveryAndBusinessResponseWaitForDurableCommit() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<ChatSendResult> response = new AtomicReference<>();
            fixture.store.completeLoad(Optional.empty());
            fixture.executor.runNext();

            fixture.agent.send(new DirectChatSendRequest(10001L, 10002L, "hello", 0), response::set);
            fixture.executor.runNext();

            assertNull(response.get());
            assertEquals(0, fixture.deliveries.get());
            assertEquals(0, fixture.agent.retainedMessages());
            assertEquals(ChatDeliveryResult.empty(), fixture.agent.deliveryStats());
            assertEquals(1, fixture.store.saves);

            fixture.store.completeSave(ChatStateSaveResult.applied(new VersionedChatState(
                    1, 5, fixture.agent.snapshotNow()
            )));
            fixture.executor.runNext();

            assertEquals(ChatSendStatus.SENT, response.get().status());
            assertEquals(1, fixture.deliveries.get());
            assertEquals(1, fixture.agent.retainedMessages());
            assertEquals(2, fixture.agent.deliveryStats().acceptedRecipients());
        }
    }

    @Test
    void failedSaveRollsBackHistoryAndDoesNotDeliver() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<ChatSendResult> response = new AtomicReference<>();
            fixture.store.completeLoad(Optional.empty());
            fixture.executor.runNext();
            fixture.agent.send(new DirectChatSendRequest(10001L, 10002L, "hello", 0), response::set);
            fixture.executor.runNext();

            fixture.store.failSave(new IllegalStateException("db unavailable"));
            fixture.executor.runNext();

            assertEquals(ChatSendStatus.BACKPRESSURED, response.get().status());
            assertEquals(0, fixture.deliveries.get());
            assertEquals(0, fixture.agent.retainedMessages());
            assertEquals(0, fixture.agent.droppedHistoryMessages());
            assertEquals(ChatDeliveryResult.empty(), fixture.agent.deliveryStats());
            assertEquals(1, fixture.agent.persistenceStats().saveFailures());
            assertEquals(0, fixture.agent.snapshotNow().revision());
            assertEquals(List.of(), fixture.agent.snapshotNow().history());
        }
    }

    @Test
    void membershipInterestIsPublishedOnlyAfterDurableCommit() {
        RecordingExecutor executor = new RecordingExecutor();
        try (ActorSystem actors = new ActorSystem(executor, 64)) {
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            ScenePlayerInterestCoordinator interests = new ScenePlayerInterestCoordinator(
                    new SceneProfileAwarenessAgent(messages, actors.actor("chat-profile-awareness")),
                    new SceneFriendAwarenessAgent(messages, actors.actor("chat-friend-awareness")),
                    new ScenePlayerDomainEventAgent(messages, actors.actor("chat-domain-awareness")),
                    new SceneAllianceAwarenessAgent(messages, actors.actor("chat-alliance-awareness"))
            );
            RecordingAsyncStore store = new RecordingAsyncStore();
            ActorRef actor = actors.actor("chat-channel-world-1");
            ChatChannelAgent agent = new ChatChannelAgent(
                    messages,
                    actor,
                    "world:1",
                    interests,
                    request -> ChatMessageDecision.sent("player", request.text()),
                    CLOCK
            );
            agent.attachPersistence(new ChatAgentPersistence(
                    ChatActorIds.channelIdentity("world:1"),
                    actor,
                    messages,
                    store,
                    5,
                    8,
                    agent::snapshotNow,
                    agent::restoreSnapshot,
                    () -> {
                    }
            ));
            store.completeLoad(Optional.empty());
            executor.runNext();

            AtomicReference<ChatJoinResult> joined = new AtomicReference<>();
            agent.join(new ChatJoinRequest("world:1", 10001L, 0), joined::set);
            executor.runNext();

            assertNull(joined.get());
            assertEquals(0, interests.interestStats().players());

            store.completeSave(ChatStateSaveResult.applied(new VersionedChatState(
                    1, 5, agent.snapshotNow()
            )));
            executor.runAll();

            assertEquals(ChatJoinStatus.JOINED, joined.get().status());
            assertEquals(1, interests.interestStats().players());

            AtomicReference<ChatLeaveResult> left = new AtomicReference<>();
            agent.leave(new ChatLeaveRequest("world:1", 10001L), left::set);
            executor.runNext();
            assertEquals(1, interests.interestStats().players());

            store.failSave(new IllegalStateException("db unavailable"));
            executor.runAll();

            assertEquals(ChatLeaveStatus.BACKPRESSURED, left.get().status());
            assertEquals(1, interests.interestStats().players());
            assertEquals(java.util.Set.of(10001L), agent.snapshotNow().members());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordingExecutor executor = new RecordingExecutor();
        private final ActorSystem actors = new ActorSystem(executor, 64);
        private final DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
        private final RecordingAsyncStore store = new RecordingAsyncStore();
        private final AtomicInteger deliveries = new AtomicInteger();
        private final ActorRef actor = actors.actor("chat-direct-10001-10002");
        private final DirectChatSessionAgent agent = new DirectChatSessionAgent(
                messages,
                actor,
                10001L,
                10002L,
                DirectChatAccessPolicy.allowAll(),
                request -> ChatMessageDecision.sent("player", request.text()),
                envelope -> {
                    deliveries.incrementAndGet();
                    return new ChatDeliveryResult(envelope.recipients().size(), 0, 0);
                },
                CLOCK,
                20
        );

        private Fixture() {
            AgentIdentity identity = ChatActorIds.directIdentity(10001L, 10002L);
            agent.attachPersistence(new ChatAgentPersistence(
                    identity,
                    actor,
                    messages,
                    store,
                    5,
                    8,
                    agent::snapshotNow,
                    agent::restoreSnapshot,
                    () -> {
                    }
            ));
        }

        @Override
        public void close() {
            actors.close();
        }
    }

    private static final class RecordingAsyncStore implements AsyncChatStateStore {
        private Consumer<Optional<VersionedChatState>> loadSuccess;
        private Consumer<ChatStateSaveResult> saveSuccess;
        private Consumer<Throwable> saveFailure;
        private int saves;

        @Override
        public void load(
                AgentIdentity identity,
                Consumer<Optional<VersionedChatState>> success,
                Consumer<Throwable> failure
        ) {
            loadSuccess = success;
        }

        @Override
        public void save(
                AgentIdentity identity,
                long expectedVersion,
                long ownerToken,
                ChatChannelSnapshot snapshot,
                Consumer<ChatStateSaveResult> success,
                Consumer<Throwable> failure
        ) {
            saves++;
            saveSuccess = success;
            saveFailure = failure;
        }

        void completeLoad(Optional<VersionedChatState> state) {
            loadSuccess.accept(state);
        }

        void completeSave(ChatStateSaveResult result) {
            saveSuccess.accept(result);
        }

        void failSave(Throwable error) {
            saveFailure.accept(error);
        }
    }

    private static final class RecordingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        void runNext() {
            commands.removeFirst().run();
        }

        void runAll() {
            while (!commands.isEmpty()) {
                runNext();
            }
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
