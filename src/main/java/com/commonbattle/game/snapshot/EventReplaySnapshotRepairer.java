package com.commonbattle.game.snapshot;

import com.commonbattle.cluster.event.EventReplayRepairer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 topic 级快照修补器适配到跨服事件 replay 修补入口。
 */
public final class EventReplaySnapshotRepairer implements EventReplayRepairer {
    private final Map<String, SnapshotRepairer> repairers = new ConcurrentHashMap<>();
    private final AtomicReference<SnapshotRepairReport> lastReport = new AtomicReference<>(new SnapshotRepairReport(java.util.List.of()));

    public EventReplaySnapshotRepairer register(String topic, SnapshotRepairer repairer) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(repairer, "repairer");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        repairers.put(topic, repairer);
        return this;
    }

    @Override
    public void repair(String topic, Set<String> ownerKeys) {
        SnapshotRepairer repairer = repairers.get(topic);
        if (repairer == null) {
            throw new IllegalStateException("No snapshot repairer for topic " + topic);
        }
        SnapshotRepairReport report = repairer.repair(ownerKeys);
        lastReport.set(report);
        if (!report.successful()) {
            throw new IllegalStateException("Snapshot repair failed for topic " + topic);
        }
    }

    public SnapshotRepairReport lastReport() {
        return lastReport.get();
    }
}
