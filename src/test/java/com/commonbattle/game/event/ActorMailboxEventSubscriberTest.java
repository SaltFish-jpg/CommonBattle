package com.commonbattle.game.event;

import com.commonbattle.actor.ActorOverflowStrategy;
import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.ActorSystemConfig;
import com.commonbattle.actor.ActorTaskCategory;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorMailboxEventSubscriberTest {
    @Test
    void eventCallbackOnlyEnqueuesAndHandlerRunsInsideTargetMailbox() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorRef scene = actors.actor("scene-1");
        AtomicReference<String> handledBy = new AtomicReference<>();
        ActorMailboxEventSubscriber subscriber = new ActorMailboxEventSubscriber(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                scene,
                (context, event) -> handledBy.set(context.self().id() + ":" + event.ownerKey())
        );

        subscriber.onEvent(profileEvent(1));

        assertEquals(null, handledBy.get());
        assertEquals(1, subscriber.stats().receivedEvents());
        assertEquals(1, subscriber.stats().enqueuedEvents());
        assertEquals(1, actors.stats().queuedTasksByCategory().get(ActorTaskCategory.EVENT));

        executor.runAll();

        assertEquals("scene-1:profile:10001", handledBy.get());
        assertEquals(1, subscriber.stats().handledEvents());
        assertEquals(0, subscriber.stats().failedEvents());
    }

    @Test
    void mailboxFullRejectsEventWithoutRunningHandler() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(
                executor,
                new ActorSystemConfig(1, 64, 1, ActorOverflowStrategy.REJECT, Duration.ZERO),
                ignored -> {
                },
                ignored -> {
                }
        );
        ActorRef scene = actors.actor("scene-1");
        actors.send(scene, ignored -> {
        });
        AtomicInteger handled = new AtomicInteger();
        ActorMailboxEventSubscriber subscriber = new ActorMailboxEventSubscriber(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                scene,
                (context, event) -> handled.incrementAndGet()
        );

        subscriber.onEvent(profileEvent(1));

        assertEquals(1, subscriber.stats().receivedEvents());
        assertEquals(0, subscriber.stats().enqueuedEvents());
        assertEquals(1, subscriber.stats().rejectedEvents());
        executor.runAll();
        assertEquals(0, handled.get());
    }

    @Test
    void handlerFailureIsRecordedAfterMailboxExecutesTask() {
        RecordingExecutor executor = new RecordingExecutor();
        ActorSystem actors = new ActorSystem(executor, 64);
        ActorMailboxEventSubscriber subscriber = new ActorMailboxEventSubscriber(
                new DefaultAgentMessagePort(actors, new NoopRpcGateway()),
                actors.actor("scene-1"),
                (context, event) -> {
                    throw new IllegalStateException("broken event handler");
                }
        );

        subscriber.onEvent(profileEvent(1));
        executor.runAll();

        assertEquals(1, subscriber.stats().enqueuedEvents());
        assertEquals(0, subscriber.stats().handledEvents());
        assertEquals(1, subscriber.stats().failedEvents());
    }

    private static ProfileChangedEvent profileEvent(long revision) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary("avatar_" + revision, "frame_1", "costume_1"),
                        AllianceBrief.none(),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
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

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
