package com.commonbattle.game.bag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内存背包变更流水。
 * 只保留最近一段窗口，适合测试、轻量运维端点和单进程样例。
 */
public final class InMemoryBagChangeAuditLog implements BagChangeAuditSink, BagChangeAuditView {
    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final ArrayDeque<BagChangeAuditRecord> records;
    private long recorded;
    private long increased;
    private long decreased;
    private long unchanged;
    private long dropped;

    public InMemoryBagChangeAuditLog() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryBagChangeAuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void record(BagChangeAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (records.size() == capacity) {
            records.removeFirst();
            dropped++;
        }
        records.addLast(record);
        recorded++;
        if (record.delta() > 0) {
            increased++;
        } else if (record.delta() < 0) {
            decreased++;
        } else {
            unchanged++;
        }
    }

    @Override
    public synchronized BagChangeAuditPage query(BagChangeAuditQuery query) {
        Objects.requireNonNull(query, "query");
        List<BagChangeAuditRecord> matched = new ArrayList<>();
        for (BagChangeAuditRecord record : records) {
            if (query.playerId() != null && query.playerId() != record.playerId()) {
                continue;
            }
            if (query.itemId() != null && !query.itemId().equals(record.itemId())) {
                continue;
            }
            if (query.reason() != null && !query.reason().equals(record.reason())) {
                continue;
            }
            matched.add(record);
        }
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new BagChangeAuditPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    @Override
    public synchronized BagChangeAuditStats stats() {
        return new BagChangeAuditStats(records.size(), recorded, increased, decreased, unchanged, dropped);
    }

    public synchronized List<BagChangeAuditRecord> records() {
        return List.copyOf(new ArrayList<>(records));
    }
}
