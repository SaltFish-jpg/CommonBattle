package com.commonbattle.observability;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryOwnerRepairOpsAuditLogTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void evictsOldestRecordsAndKeepsAggregateStats() {
        InMemoryOwnerRepairOpsAuditLog audit = new InMemoryOwnerRepairOpsAuditLog(2);

        audit.record(record("release", "friend:10001", 1));
        audit.record(record("release", "missing", 0));
        audit.record(record("release-all", "*", 2));

        OwnerRepairOpsAuditStats stats = audit.stats();
        assertEquals(2, audit.size());
        assertEquals(3, stats.recordedEntries());
        assertEquals(2, stats.retainedEntries());
        assertEquals(2, stats.releaseOps());
        assertEquals(1, stats.releaseAllOps());
        assertEquals(3, stats.releasedOwners());
        assertEquals(1, stats.notFoundReleaseOps());
        assertEquals(1, stats.droppedEntries());
    }

    @Test
    void queriesByActionOwnerAndPage() {
        InMemoryOwnerRepairOpsAuditLog audit = new InMemoryOwnerRepairOpsAuditLog(8);
        audit.record(record("release", "friend:10001", 1));
        audit.record(record("release", "missing", 0));
        audit.record(record("release-all", "*", 2));

        OwnerRepairOpsAuditPage releaseAll = audit.query(new OwnerRepairOpsAuditQuery("release-all", null, 0, 8));
        OwnerRepairOpsAuditPage missing = audit.query(new OwnerRepairOpsAuditQuery(null, "missing", 0, 8));
        OwnerRepairOpsAuditPage paged = audit.query(new OwnerRepairOpsAuditQuery(null, null, 1, 1));

        assertEquals(1, releaseAll.matched());
        assertEquals("release-all", releaseAll.entries().getFirst().action());
        assertEquals(1, missing.matched());
        assertEquals(0, missing.entries().getFirst().released());
        assertEquals(3, paged.matched());
        assertEquals("missing", paged.entries().getFirst().ownerKey());
        assertEquals(1, paged.offset());
        assertEquals(1, paged.limit());
    }

    @Test
    void canSkipRetainingNotFoundReleaseRecords() {
        InMemoryOwnerRepairOpsAuditLog audit = new InMemoryOwnerRepairOpsAuditLog(
                new OwnerRepairOpsAuditConfig(8, false)
        );

        audit.record(record("release", "missing", 0));

        OwnerRepairOpsAuditStats stats = audit.stats();
        OwnerRepairOpsAuditPage page = audit.query(new OwnerRepairOpsAuditQuery(null, null, 0, 8));
        assertEquals(0, audit.size());
        assertEquals(0, page.entries().size());
        assertEquals(1, stats.recordedEntries());
        assertEquals(1, stats.releaseOps());
        assertEquals(1, stats.notFoundReleaseOps());
        assertEquals(0, stats.retainedEntries());
    }

    @Test
    void stillRetainsSuccessfulReleaseWhenNotFoundRetentionDisabled() {
        InMemoryOwnerRepairOpsAuditLog audit = new InMemoryOwnerRepairOpsAuditLog(
                new OwnerRepairOpsAuditConfig(8, false)
        );

        audit.record(record("release", "friend:10001", 1));

        assertEquals(1, audit.size());
        assertEquals("friend:10001", audit.query(new OwnerRepairOpsAuditQuery(null, null, 0, 8))
                .entries().getFirst().ownerKey());
    }

    @Test
    void rejectsInvalidCapacityAndRecords() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryOwnerRepairOpsAuditLog(0));
        assertThrows(IllegalArgumentException.class, () -> new OwnerRepairOpsAuditConfig(0, true));
        assertThrows(IllegalArgumentException.class,
                () -> record("release", "friend:10001", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new OwnerRepairOpsAuditQuery(null, null, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OwnerRepairOpsAuditQuery(null, null, 0, 0));
    }

    private static OwnerRepairOpsAuditRecord record(String action, String ownerKey, int released) {
        return new OwnerRepairOpsAuditRecord(action, ownerKey, released, NOW, "/127.0.0.1:10000");
    }
}
