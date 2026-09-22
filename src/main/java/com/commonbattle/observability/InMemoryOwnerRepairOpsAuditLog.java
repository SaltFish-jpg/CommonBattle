package com.commonbattle.observability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内存 owner 修复运维操作审计日志。
 * 只保留最近一段窗口，适合轻量运维 HTTP 端点直接暴露排障信息。
 */
public final class InMemoryOwnerRepairOpsAuditLog implements OwnerRepairOpsAuditSink, OwnerRepairOpsAuditView {
    private final OwnerRepairOpsAuditConfig config;
    private final ArrayDeque<OwnerRepairOpsAuditRecord> records;
    private long recorded;
    private long releaseOps;
    private long releaseAllOps;
    private long releasedOwners;
    private long notFoundReleaseOps;
    private long dropped;

    public InMemoryOwnerRepairOpsAuditLog() {
        this(OwnerRepairOpsAuditConfig.defaults());
    }

    public InMemoryOwnerRepairOpsAuditLog(int capacity) {
        this(new OwnerRepairOpsAuditConfig(capacity, true));
    }

    public InMemoryOwnerRepairOpsAuditLog(OwnerRepairOpsAuditConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.records = new ArrayDeque<>(config.capacity());
    }

    @Override
    public synchronized void record(OwnerRepairOpsAuditRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.released() == 0 && "release".equals(record.action()) && !config.recordNotFoundRelease()) {
            recorded++;
            releaseOps++;
            notFoundReleaseOps++;
            return;
        }
        if (records.size() == config.capacity()) {
            records.removeFirst();
            dropped++;
        }
        records.addLast(record);
        recorded++;
        releasedOwners += record.released();
        if ("release-all".equals(record.action())) {
            releaseAllOps++;
        } else {
            releaseOps++;
            if (record.released() == 0) {
                notFoundReleaseOps++;
            }
        }
    }

    @Override
    public synchronized OwnerRepairOpsAuditPage query(OwnerRepairOpsAuditQuery query) {
        Objects.requireNonNull(query, "query");
        List<OwnerRepairOpsAuditRecord> matched = new ArrayList<>();
        for (OwnerRepairOpsAuditRecord record : records) {
            if (query.action() != null && !query.action().equals(record.action())) {
                continue;
            }
            if (query.ownerKey() != null && !query.ownerKey().equals(record.ownerKey())) {
                continue;
            }
            matched.add(record);
        }
        int from = Math.min(query.offset(), matched.size());
        int to = Math.min(from + query.limit(), matched.size());
        return new OwnerRepairOpsAuditPage(matched.subList(from, to), matched.size(), query.offset(), query.limit());
    }

    @Override
    public synchronized OwnerRepairOpsAuditStats stats() {
        return new OwnerRepairOpsAuditStats(
                records.size(),
                recorded,
                releaseOps,
                releaseAllOps,
                releasedOwners,
                notFoundReleaseOps,
                dropped
        );
    }

    public synchronized int size() {
        return records.size();
    }
}
