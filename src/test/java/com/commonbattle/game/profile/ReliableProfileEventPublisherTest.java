package com.commonbattle.game.profile;

import com.commonbattle.game.event.InMemoryVersionedEventOutbox;
import com.commonbattle.game.event.VersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableProfileEventPublisherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void savesSnapshotBeforeDelegatingPublish() {
        InMemoryProfileSnapshotRepository snapshots = new InMemoryProfileSnapshotRepository();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        List<VersionedEvent> published = new ArrayList<>();
        ReliableProfileEventPublisher publisher = new ReliableProfileEventPublisher(snapshots, outbox, event -> {
            assertTrue(snapshots.find(10001L).isPresent());
            published.add(event);
        });

        publisher.publish(event(1, "hero"));

        assertEquals(1, published.size());
        assertEquals("hero", snapshots.find(10001L).orElseThrow().name());
        assertTrue(outbox.pending().isEmpty());
    }

    @Test
    void failedPublishStaysPendingAndReplayClearsOutbox() {
        InMemoryProfileSnapshotRepository snapshots = new InMemoryProfileSnapshotRepository();
        InMemoryVersionedEventOutbox outbox = new InMemoryVersionedEventOutbox(CLOCK);
        List<VersionedEvent> published = new ArrayList<>();
        FailingOncePublisher delegate = new FailingOncePublisher(published);
        ReliableProfileEventPublisher publisher = new ReliableProfileEventPublisher(snapshots, outbox, delegate);

        publisher.publish(event(1, "hero"));

        assertEquals(1, outbox.pending().size());
        assertEquals(1, outbox.pending().getFirst().attempts());

        publisher.replayPending();

        assertTrue(outbox.pending().isEmpty());
        assertEquals(1, published.size());
    }

    @Test
    void staleLocalCacheRefreshesFromSnapshotRepository() {
        InMemoryProfileSnapshotRepository snapshots = new InMemoryProfileSnapshotRepository();
        snapshots.save(event(4, "hero-latest").snapshot());
        LocalProfileCache cache = new LocalProfileCache();
        cache.apply(event(3, "hero-gap"));

        cache.refreshFrom(snapshots, 10001L);

        assertEquals("hero-latest", cache.get(10001L).orElseThrow().snapshot().name());
        assertEquals(4, cache.revisionOf(10001L));
        assertFalse(cache.isStale(10001L));
    }

    private static ProfileChangedEvent event(long revision, String name) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.NAME),
                new PlayerProfileSnapshot(
                        10001L,
                        name,
                        10,
                        AppearanceSummary.defaults(),
                        AllianceBrief.none(),
                        new FriendBrief(0, 0),
                        revision,
                        CLOCK.instant()
                )
        );
    }

    private static final class FailingOncePublisher implements com.commonbattle.game.event.EventPublisher {
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
