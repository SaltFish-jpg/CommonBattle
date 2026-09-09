package com.commonbattle.game.profile;

import com.commonbattle.game.event.VersionedEvent;
import com.commonbattle.game.social.FriendSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendProfileEventProjectorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void projectsFriendSnapshotIntoExistingProfileBrief() {
        InMemoryProfileSnapshotRepository profiles = new InMemoryProfileSnapshotRepository();
        profiles.save(new PlayerProfileSnapshot(
                10001L,
                "hero",
                12,
                new AppearanceSummary("avatar_a", "frame_a", "costume_a"),
                new AllianceBrief(30003L, "alliance", "badge"),
                new FriendBrief(1, 3),
                8,
                Instant.parse("2026-08-31T00:00:00Z")
        ));
        List<VersionedEvent> events = new ArrayList<>();
        FriendProfileEventProjector projector = new FriendProfileEventProjector(profiles, events::add, CLOCK);

        projector.onFriendSnapshot(new FriendSnapshot(10001L, 9, Set.of(20002L, 20003L)));

        PlayerProfileSnapshot profile = profiles.find(10001L).orElseThrow();
        assertEquals("hero", profile.name());
        assertEquals(12, profile.level());
        assertEquals(new AppearanceSummary("avatar_a", "frame_a", "costume_a"), profile.appearance());
        assertEquals(new AllianceBrief(30003L, "alliance", "badge"), profile.alliance());
        assertEquals(new FriendBrief(2, 9), profile.friends());
        assertEquals(9, profile.revision());
        assertEquals(CLOCK.instant(), profile.updatedAt());
        ProfileChangedEvent event = (ProfileChangedEvent) events.getFirst();
        assertEquals(Set.of(ProfileField.FRIENDS), event.changedFields());
        assertEquals(profile, event.snapshot());
    }

    @Test
    void createsDefaultProfileWhenOnlyFriendDataExists() {
        InMemoryProfileSnapshotRepository profiles = new InMemoryProfileSnapshotRepository();
        List<VersionedEvent> events = new ArrayList<>();
        FriendProfileEventProjector projector = new FriendProfileEventProjector(profiles, events::add, CLOCK);

        projector.onFriendSnapshot(new FriendSnapshot(10001L, 3, Set.of(20002L)));

        PlayerProfileSnapshot profile = profiles.find(10001L).orElseThrow();
        assertEquals("player-10001", profile.name());
        assertEquals(1, profile.level());
        assertEquals(AppearanceSummary.defaults(), profile.appearance());
        assertEquals(AllianceBrief.none(), profile.alliance());
        assertEquals(new FriendBrief(1, 3), profile.friends());
        assertEquals(3, profile.revision());
        assertTrue(((ProfileChangedEvent) events.getFirst()).changedFields().contains(ProfileField.FRIENDS));
    }
}
