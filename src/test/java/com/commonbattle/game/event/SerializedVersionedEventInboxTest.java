package com.commonbattle.game.event;

import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedVersionedEventInboxTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void pendingRetrySurvivesRecreationAndDuplicateInsertIsIdempotent() {
        InMemoryVersionedEventInboxBytesStore store = new InMemoryVersionedEventInboxBytesStore();
        SerializedVersionedEventInbox first = inbox(store);
        ProfileChangedEvent event = event(1);

        assertEquals(EventInboxAppendStatus.APPENDED, first.appendIfAbsent(event, NOW));
        assertEquals(EventInboxAppendStatus.ALREADY_PENDING, first.appendIfAbsent(event, NOW.plusSeconds(1)));
        assertEquals(EventInboxFailureResult.RETRY_SCHEDULED, first.markAttemptFailed(
                EventInboxKey.of(event),
                new IllegalStateException("projection unavailable"),
                NOW.plusSeconds(2),
                NOW.plusSeconds(12),
                3
        ));

        SerializedVersionedEventInbox restored = inbox(store);
        PendingEventInboxEntry pending = restored.pending(ProfileChangedEvent.TOPIC).getFirst();

        assertEquals(1, pending.attempts());
        assertEquals(NOW.plusSeconds(12), pending.nextAttemptAt());
        assertEquals("projection unavailable", pending.lastFailure());
        assertTrue(restored.ready(ProfileChangedEvent.TOPIC, NOW.plusSeconds(11), 10).isEmpty());
        assertEquals(1, restored.ready(ProfileChangedEvent.TOPIC, NOW.plusSeconds(12), 10).size());
    }

    @Test
    void deadLetterBlocksLaterRevisionAcrossRestartUntilRedrive() {
        InMemoryVersionedEventInboxBytesStore store = new InMemoryVersionedEventInboxBytesStore();
        SerializedVersionedEventInbox first = inbox(store);
        ProfileChangedEvent firstEvent = event(1);
        ProfileChangedEvent secondEvent = event(2);
        first.appendIfAbsent(firstEvent, NOW);
        first.appendIfAbsent(secondEvent, NOW.plusMillis(1));
        first.markAttemptFailed(
                EventInboxKey.of(firstEvent),
                new IllegalStateException("poison"),
                NOW.plusSeconds(1),
                NOW.plusSeconds(1),
                1
        );

        SerializedVersionedEventInbox restored = inbox(store);

        assertEquals(1, restored.deadLetters(ProfileChangedEvent.TOPIC).size());
        assertTrue(restored.ready(ProfileChangedEvent.TOPIC, NOW.plusSeconds(10), 10).isEmpty());
        assertTrue(restored.redriveDeadLetter(EventInboxKey.of(firstEvent), NOW.plusSeconds(10)));
        assertEquals(1, restored.ready(ProfileChangedEvent.TOPIC, NOW.plusSeconds(10), 10).size());
        assertEquals(1, restored.ready(ProfileChangedEvent.TOPIC, NOW.plusSeconds(10), 10)
                .getFirst().key().revision());
    }

    @Test
    void sharedStoreIsIsolatedByConsumerNamespace() {
        InMemoryVersionedEventInboxBytesStore store = new InMemoryVersionedEventInboxBytesStore();
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        SerializedVersionedEventInbox sceneOne = new SerializedVersionedEventInbox(store, registry, "SCENE:r1:scene-1");
        SerializedVersionedEventInbox sceneTwo = new SerializedVersionedEventInbox(store, registry, "SCENE:r1:scene-2");

        sceneOne.appendIfAbsent(event(1), NOW);
        sceneTwo.appendIfAbsent(event(1), NOW);

        assertEquals(1, sceneOne.pending(ProfileChangedEvent.TOPIC).size());
        assertEquals(1, sceneTwo.pending(ProfileChangedEvent.TOPIC).size());
        sceneOne.markHandled(EventInboxKey.of(event(1)));
        assertTrue(sceneOne.pending(ProfileChangedEvent.TOPIC).isEmpty());
        assertEquals(1, sceneTwo.pending(ProfileChangedEvent.TOPIC).size());
    }

    private static SerializedVersionedEventInbox inbox(InMemoryVersionedEventInboxBytesStore store) {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        return new SerializedVersionedEventInbox(store, registry);
    }

    private static ProfileChangedEvent event(long revision) {
        return new ProfileChangedEvent(
                10001L,
                Set.of(ProfileField.APPEARANCE),
                new PlayerProfileSnapshot(
                        10001L,
                        "hero",
                        20,
                        new AppearanceSummary("avatar_" + revision, "frame_1", "costume_1"),
                        AllianceBrief.none(),
                        new FriendBrief(3, 1),
                        revision,
                        NOW
                )
        );
    }
}
