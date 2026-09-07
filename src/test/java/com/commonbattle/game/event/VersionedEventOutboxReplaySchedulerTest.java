package com.commonbattle.game.event;

import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedEventOutboxReplaySchedulerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void replayOnceDelegatesToReliablePublisher() {
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        List<VersionedEvent> published = new ArrayList<>();
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(outbox, published::add);
        VersionedEventOutboxReplayScheduler scheduler =
                new VersionedEventOutboxReplayScheduler(publisher, Duration.ofSeconds(1));
        outbox.append(event(1));

        scheduler.replayOnce();

        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, published.size());
        assertEquals(new VersionedEventOutboxReplaySchedulerStats(1, 0), scheduler.stats());
    }

    @Test
    void scheduledReplaySwallowsFailure() {
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(
                new FailingOutbox(),
                ignored -> {
                }
        );
        VersionedEventOutboxReplayScheduler scheduler =
                new VersionedEventOutboxReplayScheduler(publisher, Duration.ofSeconds(1));

        assertDoesNotThrow(scheduler::replaySafely);

        assertEquals(new VersionedEventOutboxReplaySchedulerStats(1, 1), scheduler.stats());
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

    private static final class FailingOutbox implements VersionedEventOutbox {
        @Override
        public PendingVersionedEvent append(VersionedEvent event) {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public List<PendingVersionedEvent> pending() {
            throw new IllegalStateException("store unavailable");
        }

        @Override
        public void markPublished(long outboxId) {
        }

        @Override
        public void markAttemptFailed(long outboxId) {
        }
    }
}
