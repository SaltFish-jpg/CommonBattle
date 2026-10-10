package com.commonbattle.game.bag;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryBagChangeAuditLogTest {
    @Test
    void recordsRecentBagChangesAndSupportsQueryFilters() {
        InMemoryBagChangeAuditLog log = new InMemoryBagChangeAuditLog(3);
        log.record(record(10001L, 1, "shop.buy", "gold", 100, 50));
        log.record(record(10001L, 1, "shop.buy", "exp_potion", 2, 3));
        log.record(record(20002L, 1, "battle.clearStage", "gold", 0, 30));
        log.record(record(10001L, 2, "bag.consumeItem", "exp_potion", 3, 1));

        BagChangeAuditPage playerPage = log.query(new BagChangeAuditQuery(10001L, null, null, 0, 10));
        BagChangeAuditPage itemPage = log.query(new BagChangeAuditQuery(null, "exp_potion", null, 0, 10));
        BagChangeAuditPage reasonPage = log.query(new BagChangeAuditQuery(null, null, "bag.consumeItem", 0, 10));

        assertEquals(2, playerPage.matched());
        assertEquals(2, itemPage.matched());
        assertEquals(1, reasonPage.matched());
        assertEquals("exp_potion", reasonPage.entries().getFirst().itemId());
        assertEquals(3, log.stats().retained());
        assertEquals(4, log.stats().recorded());
        assertEquals(2, log.stats().increased());
        assertEquals(2, log.stats().decreased());
        assertEquals(1, log.stats().dropped());
    }

    private static BagChangeAuditRecord record(
            long playerId,
            long revision,
            String reason,
            String itemId,
            int before,
            int after
    ) {
        return new BagChangeAuditRecord(
                playerId,
                revision,
                reason,
                itemId,
                before,
                after,
                Instant.parse("2026-09-01T00:00:00Z")
        );
    }
}
