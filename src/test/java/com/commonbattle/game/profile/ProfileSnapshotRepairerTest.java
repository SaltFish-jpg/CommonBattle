package com.commonbattle.game.profile;

import com.commonbattle.game.snapshot.SnapshotRepairStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSnapshotRepairerTest {
    @Test
    void refreshesLocalCacheFromRepository() {
        InMemoryProfileSnapshotRepository repository = new InMemoryProfileSnapshotRepository();
        LocalProfileCache cache = new LocalProfileCache();
        repository.save(snapshot(10001L, 3, "avatar_3"));
        ProfileSnapshotRepairer repairer = new ProfileSnapshotRepairer(repository, cache);

        var report = repairer.repair(Set.of("profile:10001"));

        assertEquals(1, report.refreshed());
        assertEquals(0, report.failed());
        assertEquals("avatar_3", cache.get(10001L).orElseThrow().snapshot().appearance().avatar());
        assertEquals(3, cache.revisionOf(10001L));
    }

    @Test
    void reportsInvalidAndMissingOwners() {
        ProfileSnapshotRepairer repairer = new ProfileSnapshotRepairer(
                new InMemoryProfileSnapshotRepository(),
                new LocalProfileCache()
        );

        var report = repairer.repair(Set.of("bad", "profile:10001"));

        assertEquals(1, report.invalidOwnerKeys());
        assertEquals(1, report.missing());
        assertTrue(report.results().stream().anyMatch(result ->
                result.status() == SnapshotRepairStatus.INVALID_OWNER_KEY));
    }

    private static PlayerProfileSnapshot snapshot(long playerId, long revision, String avatar) {
        return new PlayerProfileSnapshot(
                playerId,
                "hero",
                20,
                new AppearanceSummary(avatar, "frame_1", "costume_1"),
                AllianceBrief.none(),
                new FriendBrief(3, 1),
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
