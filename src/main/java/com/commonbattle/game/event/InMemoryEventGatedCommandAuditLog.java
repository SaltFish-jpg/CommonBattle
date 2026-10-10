package com.commonbattle.game.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内存事件门控命令审计日志。
 * 保留最近一段窗口，适合在开发、集成测试和轻量运维端点中定位跨服事件后的命令链。
 */
public final class InMemoryEventGatedCommandAuditLog
        implements EventGatedCommandAuditSink, EventGatedCommandAuditView {
    private final int capacity;
    private final ArrayDeque<EventGatedCommandAuditRecord> records;
    private long recorded;
    private long submitted;
    private long completed;
    private long failed;
    private long dropped;

    public InMemoryEventGatedCommandAuditLog() {
        this(256);
    }

    public InMemoryEventGatedCommandAuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void record(EventGatedCommandAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (records.size() == capacity) {
            records.removeFirst();
            dropped++;
        }
        records.addLast(record);
        recorded++;
        switch (record.outcome()) {
            case "submitted" -> submitted++;
            case "completed" -> completed++;
            case "failed" -> failed++;
            default -> throw new IllegalArgumentException("unsupported event gated command outcome: " + record.outcome());
        }
    }

    @Override
    public synchronized List<EventGatedCommandAuditRecord> recent() {
        return List.copyOf(new ArrayList<>(records));
    }

    @Override
    public synchronized EventGatedCommandAuditStats stats() {
        return new EventGatedCommandAuditStats(
                records.size(),
                recorded,
                submitted,
                completed,
                failed,
                dropped
        );
    }
}
