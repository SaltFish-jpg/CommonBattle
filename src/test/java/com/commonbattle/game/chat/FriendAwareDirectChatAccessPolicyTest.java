package com.commonbattle.game.chat;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.scene.SceneFriendAwarenessAgent;
import com.commonbattle.game.social.FriendChangedEvent;
import com.commonbattle.game.social.FriendRelationAction;
import com.commonbattle.game.social.FriendSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendAwareDirectChatAccessPolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void friendSnapshotAllowsDirectChatInsideSessionActor() {
        Fixture fixture = Fixture.create();
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        fixture.friendAwareness.refresh(new FriendSnapshot(10001L, 1, Set.of(10002L)));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT, sent.get().status());
        assertEquals(List.of("private"),
                fixture.directSessions.session(10001L, 10002L).snapshotNow().history().stream().map(ChatDelivery::text).toList());
    }

    @Test
    void missingFriendSnapshotRejectsDirectChatAsStale() {
        Fixture fixture = Fixture.create();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_FRIENDS, sent.get().status());
        assertEquals(List.of(), fixture.directSessions.session(10001L, 10002L).snapshotNow().history());
    }

    @Test
    void nonFriendRejectsDirectChat() {
        Fixture fixture = Fixture.create();
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        fixture.friendAwareness.refresh(new FriendSnapshot(10001L, 1, Set.of(20002L)));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), sent::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.NOT_FRIEND, sent.get().status());
        assertEquals(List.of(), fixture.directSessions.session(10001L, 10002L).snapshotNow().history());
    }

    @Test
    void staleFriendProjectionRejectsDirectChatUntilSnapshotRepair() {
        Fixture fixture = Fixture.create();
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        fixture.friendAwareness.onFriendChanged(new FriendChangedEvent(
                10001L,
                10002L,
                FriendRelationAction.ADD,
                2
        ));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> stale = new AtomicReference<>();
        AtomicReference<ChatSendResult> repaired = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), stale::set);
        fixture.executor.runAll();
        fixture.friendAwareness.refresh(new FriendSnapshot(10001L, 2, Set.of(10002L)));
        fixture.executor.runAll();
        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "after repair", 0), repaired::set);
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_FRIENDS, stale.get().status());
        assertEquals(ChatSendStatus.SENT, repaired.get().status());
        assertEquals(List.of("after repair"),
                fixture.directSessions.session(10001L, 10002L).snapshotNow().history().stream().map(ChatDelivery::text).toList());
    }

    @Test
    void friendEventDoesNotAffectDirectChatPolicyBeforeAwarenessMailboxRuns() {
        Fixture fixture = Fixture.create();
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        FriendAwareDirectChatAccessPolicy policy = new FriendAwareDirectChatAccessPolicy(fixture.friendAwareness);

        fixture.friendAwareness.onFriendChanged(new FriendChangedEvent(
                10001L,
                10002L,
                FriendRelationAction.ADD,
                1
        ));

        assertTrue(fixture.friendAwareness.friendsOf(10001L).isEmpty());
        assertEquals(ChatSendStatus.STALE_FRIENDS,
                policy.inspect(new DirectChatSendRequest(10001L, 10002L, "private", 0)));

        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT,
                policy.inspect(new DirectChatSendRequest(10001L, 10002L, "private", 0)));
    }

    @Test
    void friendEventStrictPolicyRefreshesSnapshotBeforeReturningDecision() {
        Fixture fixture = Fixture.create(playerId -> java.util.Optional.of(
                new FriendSnapshot(playerId, 3, Set.of(10002L, 30003L))
        ));
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        FriendAwareDirectChatAccessPolicy policy = new FriendAwareDirectChatAccessPolicy(fixture.friendAwareness);
        AtomicReference<ChatSendStatus> status = new AtomicReference<>();

        policy.inspectAfterFriendChanged(
                new FriendChangedEvent(10001L, 10002L, FriendRelationAction.ADD, 3),
                new DirectChatSendRequest(10001L, 10002L, "private", 0),
                status::set
        );

        assertEquals(null, status.get());

        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT, status.get());
        assertEquals(new com.commonbattle.game.scene.SceneProjectionStats(1, 1, 0, 1, 1, 1, 0, 0,
                1, 0, 1, 0, 0), fixture.friendAwareness.projectionStats());
    }

    @Test
    void friendEventStrictPolicyRejectsWhenRemoteSnapshotIsNotFreshEnough() {
        Fixture fixture = Fixture.create(playerId -> java.util.Optional.of(
                new FriendSnapshot(playerId, 2, Set.of(10002L))
        ));
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        FriendAwareDirectChatAccessPolicy policy = new FriendAwareDirectChatAccessPolicy(fixture.friendAwareness);
        AtomicReference<ChatSendStatus> status = new AtomicReference<>();

        policy.inspectAfterFriendChanged(
                new FriendChangedEvent(10001L, 10002L, FriendRelationAction.ADD, 3),
                new DirectChatSendRequest(10001L, 10002L, "private", 0),
                status::set
        );
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_FRIENDS, status.get());
        assertEquals(1, fixture.friendAwareness.projectionStats().freshReadRemoteStale());
    }

    @Test
    void directManagerSendsAfterFriendEventFreshReadInsideSessionActor() {
        Fixture fixture = Fixture.create(playerId -> java.util.Optional.of(
                new FriendSnapshot(playerId, 3, Set.of(10002L, 30003L))
        ));
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.sendAfterFriendChanged(
                new FriendChangedEvent(10001L, 10002L, FriendRelationAction.ADD, 3),
                new DirectChatSendRequest(10001L, 10002L, "private", 0),
                sent::set
        );

        assertEquals(null, sent.get());

        fixture.executor.runAll();

        assertEquals(ChatSendStatus.SENT, sent.get().status());
        assertEquals(List.of("private"),
                fixture.directSessions.session(10001L, 10002L).snapshotNow().history().stream()
                        .map(ChatDelivery::text)
                        .toList());
        assertEquals(1, fixture.directSessions.stats().asyncAccessChecks());
        assertEquals(1, fixture.directSessions.stats().asyncAccessAllowedDecisions());
        assertEquals(0, fixture.directSessions.stats().asyncAccessRejectedDecisions());
    }

    @Test
    void asyncFriendAccessReadsOutsideDirectSessionMailboxAndReentersForSend() {
        Fixture fixture = Fixture.createAsyncAccess();
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        fixture.friendAwareness.refresh(new FriendSnapshot(10001L, 1, Set.of(10002L)));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), sent::set);

        assertEquals(null, sent.get());
        assertEquals(1, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(null, sent.get());
        assertEquals(1, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(ChatSendStatus.SENT, sent.get().status());
        assertEquals(List.of("private"),
                fixture.directSessions.session(10001L, 10002L).snapshotNow().history().stream()
                        .map(ChatDelivery::text)
                        .toList());
    }

    @Test
    void asyncFriendAccessReportsBackpressureWhenDirectSessionMailboxCannotAcceptCallback() {
        Fixture fixture = Fixture.createAsyncAccessWithMailboxCapacity(1);
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        fixture.friendAwareness.refresh(new FriendSnapshot(10001L, 1, Set.of(10002L)));
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), sent::set);
        fixture.actors.send(fixture.actors.actor(ChatActorIds.directActorId(10001L, 10002L)), ignored -> {
        });

        assertEquals(2, fixture.executor.queued());

        fixture.executor.runNext();

        assertEquals(ChatSendStatus.BACKPRESSURED, sent.get().status());
        assertEquals(List.of(), fixture.directSessions.session(10001L, 10002L).snapshotNow().history());
        assertEquals(1, fixture.directSessions.stats().asyncAccessChecks());
        assertEquals(1, fixture.directSessions.stats().asyncAccessAllowedDecisions());
        assertEquals(1, fixture.directSessions.stats().asyncAccessCallbackDeliveryFailures());

        fixture.executor.runAll();
    }

    @Test
    void directManagerRejectsAfterFriendEventWhenFreshReadIsStale() {
        Fixture fixture = Fixture.create(playerId -> java.util.Optional.of(
                new FriendSnapshot(playerId, 2, Set.of(10002L))
        ));
        fixture.friendAwareness.enter(10001L);
        fixture.executor.runAll();
        AtomicReference<ChatSendResult> sent = new AtomicReference<>();

        fixture.directSessions.sendAfterFriendChanged(
                new FriendChangedEvent(10001L, 10002L, FriendRelationAction.ADD, 3),
                new DirectChatSendRequest(10001L, 10002L, "private", 0),
                sent::set
        );
        fixture.executor.runAll();

        assertEquals(ChatSendStatus.STALE_FRIENDS, sent.get().status());
        assertEquals(List.of(), fixture.directSessions.session(10001L, 10002L).snapshotNow().history());
    }

    private record Fixture(
            RecordingExecutor executor,
            ActorSystem actors,
            SceneFriendAwarenessAgent friendAwareness,
            DirectChatSessionManager directSessions
    ) {
        private static Fixture create() {
            return create(playerId -> java.util.Optional.empty());
        }

        private static Fixture createAsyncAccess() {
            return create(playerId -> java.util.Optional.empty(), true);
        }

        private static Fixture createAsyncAccessWithMailboxCapacity(int mailboxCapacity) {
            return create(playerId -> java.util.Optional.empty(), true, mailboxCapacity);
        }

        private static Fixture create(com.commonbattle.game.social.FriendSnapshotReader snapshotReader) {
            return create(snapshotReader, false);
        }

        private static Fixture create(
                com.commonbattle.game.social.FriendSnapshotReader snapshotReader,
                boolean asyncAccess
        ) {
            return create(snapshotReader, asyncAccess, com.commonbattle.actor.ActorSystemConfig.DEFAULT_MAILBOX_CAPACITY);
        }

        private static Fixture create(
                com.commonbattle.game.social.FriendSnapshotReader snapshotReader,
                boolean asyncAccess,
                int mailboxCapacity
        ) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64, mailboxCapacity);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            SceneFriendAwarenessAgent friendAwareness = new SceneFriendAwarenessAgent(
                    messages,
                    actors.actor("chat-friend-awareness"),
                    com.commonbattle.game.event.OwnerEventInterestControl.noop(),
                    snapshotReader
            );
            ChatMessagePolicy messagePolicy = request ->
                    ChatMessageDecision.sent("player-" + request.senderId(), request.text().trim());
            DirectChatSessionManager directSessions = new DirectChatSessionManager(
                    actors,
                    messages,
                    new FriendAwareDirectChatAccessPolicy(friendAwareness),
                    messagePolicy,
                    ChatDeliverySink.noop(),
                    CLOCK,
                    ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                    (target, operation) -> com.commonbattle.actor.backpressure.AdmissionDecision.accept()
            );
            if (asyncAccess) {
                FriendAwareDirectChatAccessPolicy accessPolicy = new FriendAwareDirectChatAccessPolicy(friendAwareness);
                directSessions = new DirectChatSessionManager(
                        actors,
                        messages,
                        accessPolicy,
                        accessPolicy,
                        messagePolicy,
                        ChatDeliverySink.noop(),
                        CLOCK,
                        ChatRouteConfig.DEFAULT_MAX_HISTORY_MESSAGES,
                        (target, operation) -> com.commonbattle.actor.backpressure.AdmissionDecision.accept()
                );
            }
            return new Fixture(executor, actors, friendAwareness, directSessions);
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

        private void runNext() {
            commands.removeFirst().run();
        }

        private int queued() {
            return commands.size();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
