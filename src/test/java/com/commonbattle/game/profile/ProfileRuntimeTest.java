package com.commonbattle.game.profile;

import com.commonbattle.game.event.SubscriptionDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRuntimeTest {
    @Test
    void localFastReadDoesNotCallRemoteReader() {
        LocalProfileCache cache = new LocalProfileCache();
        cache.apply(event(1, "hero", "avatar_1"));
        CountingReader reader = new CountingReader();
        ProfileRuntime runtime = new ProfileRuntime(cache, ProfileInterestControl.noop(), reader);

        ProfileReadResult result = runtime.read(10001L, ProfileReadMode.LOCAL_FAST);

        assertEquals(ProfileReadStatus.LOCAL_HIT, result.status());
        assertTrue(result.fresh());
        assertEquals("avatar_1", result.profile().orElseThrow().snapshot().appearance().avatar());
        assertEquals(0, reader.calls);
        assertEquals(new ProfileRuntimeStats(1, 1, 0, 0, 0, 0, 0), runtime.stats());
    }

    @Test
    void refreshIfStaleReadsLatestSnapshotAndClearsStaleFlag() {
        LocalProfileCache cache = new LocalProfileCache();
        cache.apply(event(3, "hero-stale", "avatar_stale"));
        CountingReader reader = new CountingReader(event(4, "hero", "avatar_4").snapshot());
        ProfileRuntime runtime = new ProfileRuntime(cache, ProfileInterestControl.noop(), reader);

        ProfileReadResult result = runtime.read(10001L, ProfileReadMode.REFRESH_IF_STALE);

        assertEquals(ProfileReadStatus.REFRESHED, result.status());
        assertTrue(result.fresh());
        assertEquals(4, result.profile().orElseThrow().snapshot().revision());
        assertFalse(cache.isStale(10001L));
        assertEquals(1, reader.calls);
        assertEquals(new ProfileRuntimeStats(1, 0, 0, 0, 1, 0, 0), runtime.stats());
    }

    @Test
    void forceRefreshUpdatesEvenWhenLocalCacheLooksFresh() {
        LocalProfileCache cache = new LocalProfileCache();
        cache.apply(event(1, "hero", "avatar_1"));
        CountingReader reader = new CountingReader(event(2, "hero", "avatar_2").snapshot());
        ProfileRuntime runtime = new ProfileRuntime(cache, ProfileInterestControl.noop(), reader);

        ProfileReadResult result = runtime.read(10001L, ProfileReadMode.FORCE_REFRESH);

        assertEquals(ProfileReadStatus.REFRESHED, result.status());
        assertEquals("avatar_2", result.profile().orElseThrow().snapshot().appearance().avatar());
        assertEquals(1, reader.calls);
    }

    @Test
    void refreshMissingRemoteFallsBackToLocalButNotFresh() {
        LocalProfileCache cache = new LocalProfileCache();
        cache.apply(event(3, "hero-stale", "avatar_stale"));
        CountingReader reader = new CountingReader();
        ProfileRuntime runtime = new ProfileRuntime(cache, ProfileInterestControl.noop(), reader);

        ProfileReadResult result = runtime.read(10001L, ProfileReadMode.REFRESH_IF_STALE);

        assertEquals(ProfileReadStatus.LOCAL_FALLBACK, result.status());
        assertTrue(result.present());
        assertFalse(result.fresh());
        assertEquals("avatar_stale", result.profile().orElseThrow().snapshot().appearance().avatar());
        assertEquals(new ProfileRuntimeStats(1, 0, 0, 0, 0, 0, 1), runtime.stats());
    }

    @Test
    void delegatesInterestLifecycleAndAppliesEvents() {
        LocalProfileCache cache = new LocalProfileCache();
        RecordingInterestControl interests = new RecordingInterestControl();
        ProfileRuntime runtime = new ProfileRuntime(cache, interests, playerId -> Optional.empty());

        runtime.watch(10001L);
        SubscriptionDecision decision = runtime.apply(event(1, "hero", "avatar_1"));
        runtime.unwatch(10001L);

        assertEquals(List.of(10001L), interests.watched);
        assertEquals(List.of(10001L), interests.unwatched);
        assertEquals(SubscriptionDecision.APPLY, decision);
        assertEquals("hero", cache.get(10001L).orElseThrow().snapshot().name());
    }

    private static ProfileChangedEvent event(long revision, String name, String avatar) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.NAME, ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        name,
                        20,
                        new AppearanceSummary(avatar, "frame_1", "costume_1"),
                        AllianceBrief.none(),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }

    private static final class CountingReader implements ProfileSnapshotReader {
        private final Optional<PlayerProfileSnapshot> snapshot;
        private int calls;

        private CountingReader() {
            this.snapshot = Optional.empty();
        }

        private CountingReader(PlayerProfileSnapshot snapshot) {
            this.snapshot = Optional.of(snapshot);
        }

        @Override
        public Optional<PlayerProfileSnapshot> find(long playerId) {
            calls++;
            return snapshot.filter(value -> value.playerId() == playerId);
        }
    }

    private static final class RecordingInterestControl implements ProfileInterestControl {
        private final List<Long> watched = new ArrayList<>();
        private final List<Long> unwatched = new ArrayList<>();

        @Override
        public void watch(long playerId) {
            watched.add(playerId);
        }

        @Override
        public void unwatch(long playerId) {
            unwatched.add(playerId);
        }
    }
}
