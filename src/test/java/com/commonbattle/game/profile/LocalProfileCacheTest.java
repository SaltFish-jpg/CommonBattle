package com.commonbattle.game.profile;

import com.commonbattle.game.event.SubscriptionDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProfileCacheTest {
    @Test
    void appliesContinuousProfileEventToLocalCache() {
        LocalProfileCache cache = new LocalProfileCache();
        ProfileChangedEvent event = event(1, "hero");

        SubscriptionDecision decision = cache.apply(event);

        assertEquals(SubscriptionDecision.APPLY, decision);
        assertEquals("hero", cache.get(10001L).orElseThrow().snapshot().name());
        assertFalse(cache.isStale(10001L));
    }

    @Test
    void ignoresOldEventAndKeepsNewerSnapshot() {
        LocalProfileCache cache = new LocalProfileCache();
        cache.apply(event(1, "hero"));
        cache.apply(event(2, "hero-new"));

        SubscriptionDecision decision = cache.apply(event(1, "old"));

        assertEquals(SubscriptionDecision.DUPLICATE_OR_OLD, decision);
        assertEquals("hero-new", cache.get(10001L).orElseThrow().snapshot().name());
        assertEquals(2, cache.revisionOf(10001L));
    }

    @Test
    void gapEventUpdatesSnapshotButMarksStale() {
        LocalProfileCache cache = new LocalProfileCache();

        SubscriptionDecision decision = cache.apply(event(3, "hero-gap"));

        assertEquals(SubscriptionDecision.GAP, decision);
        assertEquals("hero-gap", cache.get(10001L).orElseThrow().snapshot().name());
        assertTrue(cache.isStale(10001L));
    }

    @Test
    void refreshFromRepositoryDoesNotDowngradeLocalRevision() {
        LocalProfileCache cache = new LocalProfileCache();
        InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
        cache.apply(event(1, "hero"));
        cache.apply(event(2, "hero-new"));
        repository.save(event(1, "hero-old").snapshot());

        cache.refreshFrom(repository, 10001L);

        assertEquals("hero-new", cache.get(10001L).orElseThrow().snapshot().name());
        assertEquals(2, cache.revisionOf(10001L));
        assertFalse(cache.isStale(10001L));
    }

    @Test
    void refreshWithSameRevisionClearsStaleFlag() {
        LocalProfileCache cache = new LocalProfileCache();
        InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
        ProfileChangedEvent sameRevision = event(3, "hero-latest");
        cache.apply(event(3, "hero-gap"));
        repository.save(sameRevision.snapshot());

        cache.refreshFrom(repository, 10001L);

        assertEquals("hero-latest", cache.get(10001L).orElseThrow().snapshot().name());
        assertEquals(3, cache.revisionOf(10001L));
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
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }
}
