package com.commonbattle.cluster.event;

import com.commonbattle.game.profile.AllianceBrief;
import com.commonbattle.game.profile.AppearanceSummary;
import com.commonbattle.game.profile.FriendBrief;
import com.commonbattle.game.profile.PlayerProfileSnapshot;
import com.commonbattle.game.profile.ProfileChangedEvent;
import com.commonbattle.game.profile.ProfileField;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryClusterEventJournalTest {
    @Test
    void appendIsIdempotentAndTrimsOldestTopicEvents() {
        InMemoryClusterEventJournal journal = new InMemoryClusterEventJournal();
        ProfileChangedEvent first = event(1, "avatar_1");
        ProfileChangedEvent second = event(2, "avatar_2");

        assertEquals(ClusterEventJournalAppendStatus.APPENDED,
                journal.appendAndTrim(first, 1).status());
        assertEquals(ClusterEventJournalAppendStatus.DUPLICATE,
                journal.appendAndTrim(first, 1).status());
        ClusterEventJournalAppendResult appended = journal.appendAndTrim(second, 1);

        assertEquals(1, appended.trimmedEvents());
        assertEquals(Set.of(ProfileChangedEvent.TOPIC), journal.topics());
        assertEquals(java.util.List.of(second), journal.retained(ProfileChangedEvent.TOPIC));
        assertEquals(ClusterEventJournalAppendStatus.DUPLICATE,
                journal.appendAndTrim(first, 1).status());
        assertEquals(java.util.List.of(second), journal.retained(ProfileChangedEvent.TOPIC));
    }

    @Test
    void rejectsDifferentPayloadUsingSameBusinessKey() {
        InMemoryClusterEventJournal journal = new InMemoryClusterEventJournal();
        journal.appendAndTrim(event(1, "avatar_1"), 10);

        assertThrows(IllegalStateException.class,
                () -> journal.appendAndTrim(event(1, "avatar_changed"), 10));
    }

    private static ProfileChangedEvent event(long revision, String avatar) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary(avatar, "frame", "costume"),
                        new AllianceBrief(100, "alliance", "badge"),
                        new FriendBrief(3, 1),
                        revision,
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        );
    }
}
