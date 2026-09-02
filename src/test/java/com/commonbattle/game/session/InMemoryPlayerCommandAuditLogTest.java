package com.commonbattle.game.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryPlayerCommandAuditLogTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void evictsOldestRecordsWhenCapacityExceeded() {
        InMemoryPlayerCommandAuditLog audit = new InMemoryPlayerCommandAuditLog(2);

        audit.record(record(1, 1));
        audit.record(record(2, 2));
        audit.record(record(3, 3));

        assertEquals(2, audit.size());
        assertEquals(2, audit.records().getFirst().sequence());
        assertEquals(3, audit.last().sequence());
        assertEquals(new PlayerCommandAuditStats(2, 1), audit.stats());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryPlayerCommandAuditLog(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryPlayerCommandAuditLog(-1));
    }

    private static PlayerCommandAuditRecord record(long sequence, long configVersion) {
        return new PlayerCommandAuditRecord(
                10001L,
                "session-1",
                1,
                sequence,
                "bag.use",
                PlayerCommandStatus.ACCEPTED,
                PlayerCommandAuditOutcome.EXECUTED,
                configVersion,
                Duration.ofMillis(sequence),
                CLOCK.instant(),
                ""
        );
    }
}
