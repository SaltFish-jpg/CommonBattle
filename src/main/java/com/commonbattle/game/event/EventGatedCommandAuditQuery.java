package com.commonbattle.game.event;

import java.util.Objects;

/**
 * 事件门控命令审计查询条件。
 * operation 用于定位业务入口，topic/ownerKey/revision 用于定位触发命令的一致性事件边界。
 */
public record EventGatedCommandAuditQuery(
        String operation,
        String topic,
        String ownerKey,
        String outcome,
        long revision,
        int offset,
        int limit
) {
    public EventGatedCommandAuditQuery {
        operation = blankToNull(operation);
        topic = blankToNull(topic);
        ownerKey = blankToNull(ownerKey);
        outcome = blankToNull(outcome);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public boolean matches(EventGatedCommandAuditRecord record) {
        Objects.requireNonNull(record, "record");
        return (operation == null || operation.equals(record.operation()))
                && (topic == null || topic.equals(record.boundary().topic()))
                && (ownerKey == null || ownerKey.equals(record.boundary().ownerKey()))
                && (outcome == null || outcome.equals(record.outcome()))
                && (revision == 0 || revision == record.boundary().revision());
    }

    public static EventGatedCommandAuditQuery recent(int limit) {
        return new EventGatedCommandAuditQuery(null, null, null, null, 0, 0, limit);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
