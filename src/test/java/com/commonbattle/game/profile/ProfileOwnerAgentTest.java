package com.commonbattle.game.profile;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.event.InMemoryVersionedEventBus;
import com.commonbattle.game.event.VersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileOwnerAgentTest {
    @Test
    void profileOwnerPublishesFullSnapshotAfterChange() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        InMemoryVersionedEventBus events = new InMemoryVersionedEventBus();
        List<VersionedEvent> received = new ArrayList<>();
        events.subscribe(ProfileChangedEvent.TOPIC, received::add);
        ProfileOwnerAgent owner = new ProfileOwnerAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("profile-10001"),
                events,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC),
                10001L,
                "hero",
                10
        );

        owner.changeAppearance(new AppearanceSummary("avatar_2", "frame_1", "costume_9"));
        executor.runNext();

        ProfileChangedEvent event = (ProfileChangedEvent) received.getFirst();
        assertEquals(1, event.revision());
        assertEquals("avatar_2", event.snapshot().appearance().avatar());
        assertEquals(ProfileField.APPEARANCE, event.changedFields().iterator().next());
    }

    @Test
    void snapshotReadsOwnerCurrentStateInsideMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ProfileOwnerAgent owner = new ProfileOwnerAgent(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("profile-10001"),
                ignored -> {
                },
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC),
                10001L,
                "hero",
                10
        );
        AtomicReference<PlayerProfileSnapshot> snapshot = new AtomicReference<>();

        owner.rename("hero-new");
        executor.runNext();
        owner.snapshot(snapshot::set);
        executor.runNext();

        assertEquals("hero-new", snapshot.get().name());
        assertEquals(1, snapshot.get().revision());
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
