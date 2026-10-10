package com.commonbattle.game.event;

import java.util.List;
import java.util.Objects;

/**
 * 事件门控命令审计分页结果。
 */
public record EventGatedCommandAuditPage(
        List<EventGatedCommandAuditRecord> entries,
        int matched,
        int offset,
        int limit
) {
    public EventGatedCommandAuditPage {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (matched < 0 || offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("audit page counters are invalid");
        }
    }
}
