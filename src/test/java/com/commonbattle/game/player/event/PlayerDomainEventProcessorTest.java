package com.commonbattle.game.player.event;

import com.commonbattle.game.event.SubscriptionDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDomainEventProcessorTest {
    @Test
    void appliesMatchingEventAndIgnoresDuplicateRevision() {
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        List<PlayerDomainEventDelivery> deliveries = new ArrayList<>();
        processor.on(BattleStageClearedEvent.TYPE, deliveries::add);

        SubscriptionDecision first = processor.apply(event(10001L, 1, "forest-1"));
        SubscriptionDecision duplicate = processor.apply(event(10001L, 1, "forest-1"));

        assertEquals(SubscriptionDecision.APPLY, first);
        assertEquals(SubscriptionDecision.DUPLICATE_OR_OLD, duplicate);
        assertEquals(1, deliveries.size());
        assertEquals(1, processor.revisionOf(10001L));
        assertEquals(1, processor.stats().appliedEvents());
        assertEquals(1, processor.stats().duplicateOrOldEvents());
    }

    @Test
    void revisionGapMarksOwnerStaleAndStillDeliversContext() {
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        List<PlayerDomainEventDelivery> deliveries = new ArrayList<>();
        processor.on(BattleStageClearedEvent.TYPE, deliveries::add);

        SubscriptionDecision decision = processor.apply(event(10001L, 3, "forest-1"));

        assertEquals(SubscriptionDecision.GAP, decision);
        assertEquals(1, deliveries.size());
        assertTrue(deliveries.getFirst().stale());
        assertTrue(processor.stale(10001L));
        assertEquals(3, processor.revisionOf(10001L));
        assertEquals(1, processor.stats().gapEvents());
        assertEquals(1, processor.stats().staleOwners());
    }

    @Test
    void repairOwnerClearsStaleMarkerAndResetsCursor() {
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        processor.apply(event(10001L, 3, "forest-1"));

        processor.repairOwner(PlayerDomainVersionedEvent.ownerKey(10001L), 3);
        SubscriptionDecision decision = processor.apply(event(10001L, 4, "forest-1"));

        assertEquals(SubscriptionDecision.APPLY, decision);
        assertEquals(4, processor.revisionOf(10001L));
        assertEquals(0, processor.stats().staleOwners());
    }

    private static PlayerDomainVersionedEvent event(long playerId, long revision, String subject) {
        return new PlayerDomainVersionedEvent(
                playerId,
                BattleStageClearedEvent.TYPE,
                subject,
                1,
                revision,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
