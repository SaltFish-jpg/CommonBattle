package com.commonbattle.game.event;

import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableVersionedEventPublisherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void failedPublishStaysPendingAndReplayClearsOutbox() {
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        List<VersionedEvent> published = new ArrayList<>();
        FailingOncePublisher delegate = new FailingOncePublisher(published);
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(
                outbox,
                delegate,
                new VersionedEventRetryPolicy(3, Duration.ZERO, Duration.ZERO, 10),
                CLOCK
        );

        publisher.publish(event(1));

        assertEquals(1, outbox.pending().size());
        assertEquals(1, outbox.pending().getFirst().attempts());

        publisher.replayPending();

        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, published.size());
    }

    @Test
    void keepsOutboxUntilDownstreamAcknowledgesAndSuppressesConcurrentReplay() {
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        ManualConfirmablePublisher delegate = new ManualConfirmablePublisher();
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(outbox, delegate);

        publisher.publish(event(1));
        publisher.replayPending();

        assertEquals(1, delegate.dispatches);
        assertEquals(1, outbox.pending().size());
        assertEquals(1, publisher.stats().inFlight());

        delegate.completeSuccess();

        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, publisher.stats().confirmed());
        assertEquals(0, publisher.stats().inFlight());
    }

    @Test
    void backsOffThenDeadLettersAndSupportsOperatorRedrive() {
        MutableClock clock = new MutableClock(CLOCK.instant());
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(clock);
        SwitchingConfirmablePublisher delegate = new SwitchingConfirmablePublisher();
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(
                outbox,
                delegate,
                new VersionedEventRetryPolicy(2, Duration.ofSeconds(5), Duration.ofSeconds(30), 10),
                clock
        );

        publisher.publish(event(1));
        publisher.replayPending();

        assertEquals(1, delegate.dispatches);
        assertEquals(clock.instant().plusSeconds(5), outbox.pending().getFirst().nextAttemptAt());

        clock.advance(Duration.ofSeconds(5));
        publisher.replayPending();

        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, outbox.deadLetters().size());
        assertEquals(2, outbox.deadLetters().getFirst().attempts());
        assertEquals("center unavailable", outbox.deadLetters().getFirst().lastFailure());

        delegate.fail = false;
        assertTrue(publisher.redriveDeadLetter(outbox.deadLetters().getFirst().id()));
        assertTrue(outbox.pending().isEmpty());
        assertTrue(outbox.deadLetters().isEmpty());
        assertFalse(publisher.redriveDeadLetter(999));
    }

    private static PlayerDomainVersionedEvent event(long revision) {
        return new PlayerDomainVersionedEvent(
                10001L,
                BattleStageClearedEvent.TYPE,
                "forest-1",
                1,
                revision,
                CLOCK.instant()
        );
    }

    private static final class FailingOncePublisher implements EventPublisher {
        private final List<VersionedEvent> published;
        private boolean fail = true;

        private FailingOncePublisher(List<VersionedEvent> published) {
            this.published = published;
        }

        @Override
        public void publish(VersionedEvent event) {
            if (fail) {
                fail = false;
                throw new IllegalStateException("temporary network failure");
            }
            published.add(event);
        }
    }

    private static final class ManualConfirmablePublisher implements ConfirmableEventPublisher {
        private final AtomicReference<EventPublishCallback> callback = new AtomicReference<>();
        private int dispatches;

        @Override
        public void publish(VersionedEvent event, EventPublishCallback callback) {
            dispatches++;
            this.callback.set(callback);
        }

        private void completeSuccess() {
            callback.getAndSet(null).success();
        }
    }

    private static final class SwitchingConfirmablePublisher implements ConfirmableEventPublisher {
        private boolean fail = true;
        private int dispatches;

        @Override
        public void publish(VersionedEvent event, EventPublishCallback callback) {
            dispatches++;
            if (fail) {
                callback.failure(new IllegalStateException("center unavailable"));
            } else {
                callback.success();
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
