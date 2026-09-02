package com.commonbattle.game.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内存玩家命令审计日志。
 * 只保留最近一段窗口，避免高并发命令把本地运维观察内存无限撑大。
 */
public final class InMemoryPlayerCommandAuditLog implements PlayerCommandAuditSink, PlayerCommandAuditView {
    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final ArrayDeque<PlayerCommandAuditRecord> records;
    private long dropped;

    public InMemoryPlayerCommandAuditLog() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryPlayerCommandAuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void record(PlayerCommandAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (records.size() == capacity) {
            records.removeFirst();
            dropped++;
        }
        records.addLast(record);
    }

    public synchronized List<PlayerCommandAuditRecord> records() {
        return List.copyOf(new ArrayList<>(records));
    }

    public synchronized PlayerCommandAuditRecord last() {
        return records.getLast();
    }

    public synchronized int size() {
        return records.size();
    }

    @Override
    public synchronized PlayerCommandAuditStats stats() {
        return new PlayerCommandAuditStats(records.size(), dropped);
    }
}
