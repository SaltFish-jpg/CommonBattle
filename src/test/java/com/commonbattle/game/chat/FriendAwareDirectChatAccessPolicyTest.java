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
        fixture.friendAwareness.handleSnapshot(new FriendSnapshot(10001L, 1, Set.of(10002L)));
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
        fixture.friendAwareness.handleSnapshot(new FriendSnapshot(10001L, 1, Set.of(20002L)));
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
        fixture.friendAwareness.handleFriendChanged(new FriendChangedEvent(
                10001L,
                10002L,
                FriendRelationAction.ADD,
                2
        ));
        AtomicReference<ChatSendResult> stale = new AtomicReference<>();
        AtomicReference<ChatSendResult> repaired = new AtomicReference<>();

        fixture.directSessions.send(new DirectChatSendRequest(10001L, 10002L, "private", 0), stale::set);
        fixture.executor.runAll();
        fixture.friendAwareness.handleSnapshot(new FriendSnapshot(10001L, 2, Set.of(10002L)));
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

    private record Fixture(
            RecordingExecutor executor,
            ActorSystem actors,
            SceneFriendAwarenessAgent friendAwareness,
            DirectChatSessionManager directSessions
    ) {
        private static Fixture create() {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            SceneFriendAwarenessAgent friendAwareness = new SceneFriendAwarenessAgent(
                    messages,
                    actors.actor("chat-friend-awareness")
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
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
