package com.commonbattle.game.player.event;

import com.commonbattle.game.bag.BagChangeAuditQuery;
import com.commonbattle.game.bag.InMemoryBagChangeAuditLog;
import com.commonbattle.game.event.SubscriptionDecision;
import com.commonbattle.game.player.PlayerBusinessOperations;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BagChangeAuditEventHandlerTest {
    @Test
    void bagChangedEventIsRecordedAfterProcessorDeduplicatesRevision() {
        InMemoryBagChangeAuditLog audits = new InMemoryBagChangeAuditLog();
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        processor.on(BagChangedEvent.TYPE, new BagChangeAuditEventHandler(audits));

        SubscriptionDecision first = processor.apply(bagEvent(10001L, 1));
        SubscriptionDecision duplicate = processor.apply(bagEvent(10001L, 1));

        assertEquals(SubscriptionDecision.APPLY, first);
        assertEquals(SubscriptionDecision.DUPLICATE_OR_OLD, duplicate);
        assertEquals(2, audits.stats().recorded());
        assertEquals(2, audits.stats().retained());
        assertEquals(1, audits.stats().increased());
        assertEquals(1, audits.stats().decreased());
        assertEquals(2, audits.query(new BagChangeAuditQuery(10001L, null, null, 0, 10)).matched());
        assertEquals(1, audits.query(new BagChangeAuditQuery(null, "gold", null, 0, 10)).matched());
        assertEquals(2, audits.query(new BagChangeAuditQuery(null, null,
                PlayerBusinessOperations.SHOP_BUY, 0, 10)).matched());
    }

    @Test
    void revisionGapStillRecordsDeliveredEventAndMarksOwnerStale() {
        InMemoryBagChangeAuditLog audits = new InMemoryBagChangeAuditLog();
        PlayerDomainEventProcessor processor = new PlayerDomainEventProcessor();
        processor.on(BagChangedEvent.TYPE, new BagChangeAuditEventHandler(audits));

        SubscriptionDecision decision = processor.apply(bagEvent(10001L, 3));

        assertEquals(SubscriptionDecision.GAP, decision);
        assertEquals(2, audits.stats().recorded());
        assertEquals(3, processor.revisionOf(10001L));
        assertEquals(true, processor.stale(10001L));
    }

    private static PlayerDomainVersionedEvent bagEvent(long playerId, long revision) {
        return new PlayerDomainVersionedEvent(
                playerId,
                BagChangedEvent.TYPE,
                PlayerBusinessOperations.SHOP_BUY,
                51,
                revision,
                Instant.parse("2026-09-01T00:00:00Z"),
                Map.of(
                        "reason", PlayerBusinessOperations.SHOP_BUY,
                        "changeCount", "2",
                        "change.0.itemId", "gold",
                        "change.0.before", "100",
                        "change.0.after", "50",
                        "change.1.itemId", "exp_potion",
                        "change.1.before", "2",
                        "change.1.after", "3"
                )
        );
    }
}
