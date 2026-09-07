package com.commonbattle.game.event;

import com.commonbattle.cluster.event.ClusterEventPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.player.event.BattleStageClearedEvent;
import com.commonbattle.game.player.event.PlayerDomainVersionedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedVersionedEventOutboxTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void pendingEventsSurviveOutboxRecreationFromBytesStore() {
        InMemoryVersionedEventOutboxBytesStore store = new InMemoryVersionedEventOutboxBytesStore();
        SerializedVersionedEventOutbox first = outbox(store);

        PendingVersionedEvent appended = first.append(event(1));
        SerializedVersionedEventOutbox restored = outbox(store);

        PendingVersionedEvent pending = restored.pending().getFirst();
        PlayerDomainVersionedEvent event = assertInstanceOf(PlayerDomainVersionedEvent.class, pending.event());
        assertEquals(appended.id(), pending.id());
        assertEquals(10001L, event.playerId());
        assertEquals(BattleStageClearedEvent.TYPE, event.eventType());
        assertEquals(1, event.revision());
        assertEquals(CLOCK.instant(), pending.createdAt());
    }

    @Test
    void failedAttemptIsPersistedAndReplayCanClearRestoredOutbox() {
        InMemoryVersionedEventOutboxBytesStore store = new InMemoryVersionedEventOutboxBytesStore();
        SerializedVersionedEventOutbox first = outbox(store);
        PendingVersionedEvent pending = first.append(event(1));
        first.markAttemptFailed(pending.id());
        SerializedVersionedEventOutbox restored = outbox(store);
        List<VersionedEvent> published = new ArrayList<>();
        ReliableVersionedEventPublisher publisher = new ReliableVersionedEventPublisher(restored, published::add);

        assertEquals(1, restored.pending().getFirst().attempts());

        publisher.replayPending();

        assertTrue(restored.pending().isEmpty());
        assertEquals(1, published.size());
    }

    private static SerializedVersionedEventOutbox outbox(InMemoryVersionedEventOutboxBytesStore store) {
        PayloadCodecRegistry registry = ClusterEventPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults());
        return new SerializedVersionedEventOutbox(store, registry, CLOCK);
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
}
