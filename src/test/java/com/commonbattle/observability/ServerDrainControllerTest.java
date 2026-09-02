package com.commonbattle.observability;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.cluster.ClusterDirectory;
import com.commonbattle.cluster.InMemoryServiceRegistry;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerDrainControllerTest {
    @Test
    void idleRuntimeDrainsImmediately() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        ServerDrainController controller = new ServerDrainController(probe(clock, outbox), clock);

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertEquals(0, result.elapsed().toMillis());
    }

    @Test
    void waitsUntilOutboxIsCleared() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        outbox.append(profileEvent(clock));
        ServerDrainController controller = new ServerDrainController(probe(clock, outbox), clock, duration -> {
            clock.advance(duration);
            outbox.markPublished(1);
        });

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofSeconds(1), Duration.ofMillis(10)));

        assertTrue(result.drained());
        assertEquals(10, result.elapsed().toMillis());
    }

    @Test
    void returnsTimeoutWhenRuntimeNeverDrains() throws InterruptedException {
        MutableClock clock = new MutableClock();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        outbox.append(profileEvent(clock));
        ServerDrainController controller = new ServerDrainController(probe(clock, outbox), clock,
                duration -> clock.advance(duration));

        DrainResult result = controller.awaitDrained(new DrainConfig(Duration.ofMillis(30), Duration.ofMillis(10)));

        assertFalse(result.drained());
        assertEquals(30, result.elapsed().toMillis());
        assertEquals(1, result.lastSnapshot().outbox().pendingEvents());
    }

    private static RuntimeHealthProbe probe(Clock clock, InMemoryVersionedEventOutbox outbox) {
        ActorSystem actors = new ActorSystem(new InlineExecutor(), 64);
        return new RuntimeHealthProbe(
                clock,
                actors,
                new AgentLifecycleManager(
                        ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                        actors,
                        new InMemoryAgentDirectory(),
                        clock
                ),
                outbox,
                new ClusterDirectory(new InMemoryServiceRegistry()),
                RuntimeHealthPolicy.defaults()
        );
    }

    private static ProfileChangedEvent profileEvent(Clock clock) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.NAME),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        10,
                        AppearanceSummary.defaults(),
                        AllianceBrief.none(),
                        new FriendBrief(0, 0),
                        1,
                        clock.instant()
                )
        );
    }

    private static final class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
