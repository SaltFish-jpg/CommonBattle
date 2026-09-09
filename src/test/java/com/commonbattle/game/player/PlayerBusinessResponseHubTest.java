package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerBusinessResponseHubTest {
    @Test
    void completedResponseRemovesWaiterAndUpdatesStats() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        PlayerBusinessResponseHub hub = new PlayerBusinessResponseHub(PlayerBusinessResultSink.NOOP, clock);
        AtomicReference<PlayerBusinessResponse> received = new AtomicReference<>();
        PlayerCommand command = command(1);

        hub.expect(command, received::set);
        clock.advance(Duration.ofMillis(25));
        hub.completed(command, PlayerBusinessResponse.success(command, PlayerBusinessAck.OK));

        assertEquals(PlayerBusinessAck.OK, received.get().payload());
        assertEquals(0, hub.pendingResponses());
        assertEquals(1, hub.stats().submittedResponses());
        assertEquals(1, hub.stats().completedResponses());
        assertEquals(0, hub.stats().oldestPendingAgeMillis());
    }

    @Test
    void duplicatePendingWaitersShareSameCompletion() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        PlayerBusinessResponseHub hub = new PlayerBusinessResponseHub(PlayerBusinessResultSink.NOOP, clock);
        AtomicReference<PlayerBusinessResponse> first = new AtomicReference<>();
        AtomicReference<PlayerBusinessResponse> second = new AtomicReference<>();
        PlayerCommand command = command(2);

        hub.expect(command, first::set);
        hub.expect(command, second::set);
        hub.completed(command, PlayerBusinessResponse.success(command, PlayerBusinessAck.OK));

        assertEquals(PlayerBusinessAck.OK, first.get().payload());
        assertEquals(PlayerBusinessAck.OK, second.get().payload());
        assertEquals(0, hub.pendingResponses());
        assertEquals(1, hub.stats().submittedResponses());
        assertEquals(1, hub.stats().sharedWaiters());
        assertEquals(1, hub.stats().completedResponses());
        assertEquals(1, hub.stats().cachedResponses());
    }

    @Test
    void completedResponseCanBeReplayedForRetry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        PlayerBusinessResponseHub hub = new PlayerBusinessResponseHub(PlayerBusinessResultSink.NOOP, clock);
        AtomicReference<PlayerBusinessResponse> first = new AtomicReference<>();
        AtomicReference<PlayerBusinessResponse> replayed = new AtomicReference<>();
        PlayerCommand command = command(3);

        hub.expect(command, first::set);
        hub.completed(command, PlayerBusinessResponse.success(command, PlayerBusinessAck.OK));
        hub.expect(command, replayed::set);

        assertEquals(PlayerBusinessAck.OK, first.get().payload());
        assertEquals(PlayerBusinessAck.OK, replayed.get().payload());
        assertEquals(1, hub.stats().completedResponses());
        assertEquals(1, hub.stats().replayedResponses());
        assertEquals(1, hub.stats().cachedResponses());
    }

    @Test
    void cancelledRegistrationRemovesWaiterOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        PlayerBusinessResponseHub hub = new PlayerBusinessResponseHub(PlayerBusinessResultSink.NOOP, clock);
        PlayerBusinessResponseRegistration registration = hub.expect(command(4), ignored -> {
        });

        registration.cancel();
        registration.cancel();

        assertEquals(0, hub.pendingResponses());
        assertEquals(1, hub.stats().submittedResponses());
        assertEquals(1, hub.stats().cancelledResponses());
    }

    @Test
    void timeoutCompletesWaiterWithFailureEnvelope() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        PlayerBusinessResponseHub hub = new PlayerBusinessResponseHub(PlayerBusinessResultSink.NOOP, clock);
        AtomicReference<PlayerBusinessResponse> received = new AtomicReference<>();
        PlayerCommand command = command(5);
        hub.expect(command, received::set);
        assertTrue(hub.canComplete(command));

        boolean timedOut = hub.timeout(command, Duration.ofSeconds(5));

        assertTrue(timedOut);
        assertFalse(hub.canComplete(command));
        assertEquals(PlayerBusinessResponseStatus.FAILED, received.get().status());
        assertEquals(PlayerBusinessResponse.TIMEOUT, received.get().code());
        assertEquals(null, received.get().payload());
        assertEquals(0, hub.pendingResponses());
        assertEquals(1, hub.stats().timedOutResponses());
        assertFalse(hub.timeout(command, Duration.ofSeconds(5)));
    }

    @Test
    void fallbackRecordsUnmatchedResponseAndKeepsOldestPendingAge() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        RecordingSink fallback = new RecordingSink();
        PlayerBusinessResponseHub hub = new PlayerBusinessResponseHub(fallback, clock);
        PlayerCommand pending = command(6);
        hub.expect(pending, ignored -> {
        });
        clock.advance(Duration.ofMillis(80));
        PlayerCommand unmatched = command(7);

        hub.completed(unmatched, PlayerBusinessResponse.success(unmatched, PlayerBusinessAck.OK));

        assertEquals(1, fallback.succeeded);
        assertEquals(1, hub.pendingResponses());
        assertEquals(1, hub.stats().fallbackResponses());
        assertEquals(80, hub.stats().oldestPendingAgeMillis());
    }

    private static PlayerCommand command(long sequence) {
        return new PlayerCommand(
                10001L,
                "session-1",
                1,
                sequence,
                PlayerBusinessOperations.ACTIVITY_PROGRESS,
                new ActivityProgressCommand("kill-3", 1)
        );
    }

    private static final class RecordingSink implements PlayerBusinessResultSink {
        int succeeded;
        int failed;

        @Override
        public void succeeded(PlayerCommand command, Object response) {
            succeeded++;
        }

        @Override
        public void failed(PlayerCommand command, Throwable error) {
            failed++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
