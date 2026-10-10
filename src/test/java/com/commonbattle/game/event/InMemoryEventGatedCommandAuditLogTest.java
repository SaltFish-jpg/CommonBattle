package com.commonbattle.game.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryEventGatedCommandAuditLogTest {
    @Test
    void recordsRecentEventGatedCommandAuditStats() {
        InMemoryEventGatedCommandAuditLog audits = new InMemoryEventGatedCommandAuditLog(2);
        EventGatedCommandBoundary boundary = new EventGatedCommandBoundary("friend.changed", "friend:10001", 3);

        audits.record(new EventGatedCommandAuditRecord(
                "chat.direct.send.after_friend_event",
                "DirectChatSendRequest",
                boundary,
                "submitted",
                "",
                Instant.parse("2026-09-01T00:00:00Z")
        ));
        audits.record(new EventGatedCommandAuditRecord(
                "chat.direct.send.after_friend_event",
                "DirectChatSendRequest",
                boundary,
                "completed",
                "SENT",
                Instant.parse("2026-09-01T00:00:01Z")
        ));
        audits.record(new EventGatedCommandAuditRecord(
                "chat.direct.send.after_friend_event",
                "DirectChatSendRequest",
                boundary,
                "failed",
                "timeout",
                Instant.parse("2026-09-01T00:00:02Z")
        ));

        assertEquals(new EventGatedCommandAuditStats(2, 3, 1, 1, 1, 1), audits.stats());
        assertEquals("completed", audits.recent().getFirst().outcome());
        assertEquals("failed", audits.recent().get(1).outcome());
    }

    @Test
    void rejectsUnknownOutcome() {
        InMemoryEventGatedCommandAuditLog audits = new InMemoryEventGatedCommandAuditLog();

        assertThrows(IllegalArgumentException.class, () -> audits.record(new EventGatedCommandAuditRecord(
                "op",
                "Command",
                new EventGatedCommandBoundary("topic", "owner:1", 1),
                "unknown",
                "",
                Instant.parse("2026-09-01T00:00:00Z")
        )));
    }

    @Test
    void queryFiltersByEventBoundaryAndOutcome() {
        InMemoryEventGatedCommandAuditLog audits = new InMemoryEventGatedCommandAuditLog();
        audits.record(new EventGatedCommandAuditRecord(
                "chat.direct.send.after_friend_event",
                "DirectChatSendRequest",
                new EventGatedCommandBoundary("friend.changed", "friend:10001", 3),
                "completed",
                "SENT",
                Instant.parse("2026-09-01T00:00:00Z")
        ));
        audits.record(new EventGatedCommandAuditRecord(
                "chat.alliance.send.after_alliance_event",
                "AllianceChatSendRequest",
                new EventGatedCommandBoundary("alliance.member.changed", "alliance:100", 8),
                "completed",
                "STALE_ALLIANCE",
                Instant.parse("2026-09-01T00:00:01Z")
        ));

        EventGatedCommandAuditPage page = audits.query(new EventGatedCommandAuditQuery(
                null,
                "friend.changed",
                "friend:10001",
                "completed",
                3,
                0,
                10
        ));

        assertEquals(1, page.matched());
        assertEquals("chat.direct.send.after_friend_event", page.entries().getFirst().operation());
        assertEquals("SENT", page.entries().getFirst().result());
    }
}
