package com.commonbattle.game.event;

import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableVersionedEventPublisherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void failedPublishStaysPendingAndReplayClearsOutbox() {
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        List<VersionedEvent> published = new ArrayList<>();
        FailingOncePublisher delegate = new FailingOncePublisher(published);
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(outbox, delegate);

        publisher.publish(event(1));

        assertEquals(1, outbox.pending().size());
        assertEquals(1, outbox.pending().getFirst().attempts());

        publisher.replayPending();

        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, published.size());
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
}
