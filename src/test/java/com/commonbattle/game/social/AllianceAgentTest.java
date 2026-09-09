package com.commonbattle.game.social;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.VersionedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AllianceAgentTest {
    @Test
    void joinAndLeavePublishVersionedEventsFromOwnerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        List<VersionedEvent> events = new ArrayList<>();
        ActorRef allianceRef = actors.actor("alliance-100");
        AllianceAgent alliance = new AllianceAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                allianceRef,
                100,
                events::add
        );

        alliance.join(10001L);
        executor.runNext();
        alliance.leave(10001L);
        executor.runNext();

        AllianceMemberChangedEvent join = (AllianceMemberChangedEvent) events.get(0);
        AllianceMemberChangedEvent leave = (AllianceMemberChangedEvent) events.get(1);
        assertEquals(AllianceMemberAction.JOIN, join.action());
        assertEquals(1, join.revision());
        assertEquals(AllianceMemberAction.LEAVE, leave.action());
        assertEquals(2, leave.revision());
    }

    @Test
    void snapshotReadsCurrentAllianceStateInsideOwnerMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        AllianceAgent alliance = new AllianceAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("alliance-100"),
                100,
                ignored -> {
                }
        );
        AtomicReference<AllianceSnapshot> snapshot = new AtomicReference<>();

        alliance.join(10001L);
        executor.runNext();
        alliance.snapshot(snapshot::set);
        executor.runNext();

        assertEquals(1, snapshot.get().revision());
        assertEquals(java.util.Set.of(10001L), snapshot.get().members());
    }

    @Test
    void joinWritesSnapshotBeforePublishingEvent() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryAllianceSnapshotRepository snapshots = new InMemoryAllianceSnapshotRepository();
        List<VersionedEvent> events = new ArrayList<>();
        AllianceAgent alliance = new AllianceAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("alliance-100"),
                100,
                events::add,
                snapshots
        );

        alliance.join(10001L);
        executor.runNext();

        AllianceMemberChangedEvent event = (AllianceMemberChangedEvent) events.getFirst();
        AllianceSnapshot snapshot = snapshots.find(100).orElseThrow();
        assertEquals(event.revision(), snapshot.revision());
        assertEquals(java.util.Set.of(10001L), snapshot.members());
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
