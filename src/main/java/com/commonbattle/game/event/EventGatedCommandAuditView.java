package com.commonbattle.game.event;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件门控命令审计读取端。
 */
public interface EventGatedCommandAuditView {
    List<EventGatedCommandAuditRecord> recent();

    EventGatedCommandAuditStats stats();

    default EventGatedCommandAuditPage query(EventGatedCommandAuditQuery query) {
        List<EventGatedCommandAuditRecord> matched = new ArrayList<>();
        for (EventGatedCommandAuditRecord record : recent()) {
            if (query.matches(record)) {
                matched.add(record);
            }
        }
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new EventGatedCommandAuditPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }
}
