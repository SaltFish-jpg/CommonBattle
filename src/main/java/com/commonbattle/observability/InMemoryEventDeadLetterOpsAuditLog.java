package com.commonbattle.observability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 有界的事件死信人工操作审计日志。
 */
public final class InMemoryEventDeadLetterOpsAuditLog {
    private final int capacity;
    private final ArrayDeque<EventDeadLetterOpsAuditRecord> records;

    public InMemoryEventDeadLetterOpsAuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    public synchronized void record(EventDeadLetterOpsAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (records.size() == capacity) {
            records.removeFirst();
        }
        records.addLast(record);
    }

    public synchronized List<EventDeadLetterOpsAuditRecord> recent(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<EventDeadLetterOpsAuditRecord> result = new ArrayList<>(Math.min(limit, records.size()));
        var iterator = records.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) {
            result.add(iterator.next());
        }
        return List.copyOf(result);
    }
}
