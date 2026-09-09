package com.commonbattle.game.social;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.FriendProfileEventProjector;
import com.commonbattle.game.profile.InMemoryProfileSnapshotRepository;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FriendAgentTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void addAndRemovePublishVersionedEventsFromOwnerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        List<VersionedEvent> events = new ArrayList<>();
        ActorRef friendRef = actors.actor("friend-10001");
        FriendAgent friends = new FriendAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                friendRef,
                10001L,
                events::add
        );

        friends.add(20002L);
        executor.runNext();
        friends.remove(20002L);
        executor.runNext();

        FriendChangedEvent added = (FriendChangedEvent) events.get(0);
        FriendChangedEvent removed = (FriendChangedEvent) events.get(1);
        assertEquals(FriendRelationAction.ADD, added.action());
        assertEquals(1, added.revision());
        assertEquals(FriendRelationAction.REMOVE, removed.action());
        assertEquals(2, removed.revision());
    }

    @Test
    void snapshotReadsCurrentFriendStateInsideOwnerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        FriendAgent friends = new FriendAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("friend-10001"),
                10001L,
                ignored -> {
                }
        );
        AtomicReference<FriendSnapshot> snapshot = new AtomicReference<>();

        friends.add(20002L);
        executor.runNext();
        friends.snapshot(snapshot::set);
        executor.runNext();

        assertEquals(1, snapshot.get().revision());
        assertEquals(java.util.Set.of(20002L), snapshot.get().friends());
    }

    @Test
    void addWritesSnapshotBeforePublishingEvent() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryFriendSnapshotRepository snapshots = new InMemoryFriendSnapshotRepository();
        List<VersionedEvent> events = new ArrayList<>();
        FriendAgent friends = new FriendAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("friend-10001"),
                10001L,
                events::add,
                snapshots
        );

        friends.add(20002L);
        executor.runNext();

        FriendChangedEvent event = (FriendChangedEvent) events.getFirst();
        FriendSnapshot snapshot = snapshots.find(10001L).orElseThrow();
        assertEquals(event.revision(), snapshot.revision());
        assertEquals(java.util.Set.of(20002L), snapshot.friends());
    }

    @Test
    void addProjectsProfileBriefBeforePublishingRelationEvent() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryFriendSnapshotRepository friendSnapshots = new InMemoryFriendSnapshotRepository();
        InMemoryProfileSnapshotRepository profileSnapshots = new InMemoryProfileSnapshotRepository();
        List<VersionedEvent> profileEvents = new ArrayList<>();
        List<VersionedEvent> relationEvents = new ArrayList<>();
        FriendAgent friends = new FriendAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("friend-10001"),
                10001L,
                event -> {
                    assertEquals(new FriendBrief(1, 1), profileSnapshots.find(10001L).orElseThrow().friends());
                    relationEvents.add(event);
                },
                friendSnapshots,
                new FriendProfileEventProjector(profileSnapshots, profileEvents::add, CLOCK)
        );

        friends.add(20002L);
        executor.runNext();

        assertEquals(1, relationEvents.size());
        assertEquals(FriendRelationAction.ADD, ((FriendChangedEvent) relationEvents.getFirst()).action());
        assertEquals(new FriendBrief(1, 1), profileSnapshots.find(10001L).orElseThrow().friends());
        ProfileChangedEvent profileEvent = (ProfileChangedEvent) profileEvents.getFirst();
        assertEquals(java.util.Set.of(ProfileField.FRIENDS), profileEvent.changedFields());
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
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
