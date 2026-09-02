package com.commonbattle.game.snapshot;

import java.util.List;

/**
 * 一批 owner 的快照修补报告。
 */
public record SnapshotRepairReport(List<SnapshotRepairResult> results) {
    public SnapshotRepairReport {
        results = List.copyOf(results);
    }

    public int refreshed() {
        return count(SnapshotRepairStatus.REFRESHED);
    }

    public int missing() {
        return count(SnapshotRepairStatus.MISSING);
    }

    public int invalidOwnerKeys() {
        return count(SnapshotRepairStatus.INVALID_OWNER_KEY);
    }

    public int failed() {
        return count(SnapshotRepairStatus.FAILED);
    }

    public boolean successful() {
        return failed() == 0 && invalidOwnerKeys() == 0;
    }

    private int count(SnapshotRepairStatus status) {
        return (int) results.stream().filter(result -> result.status() == status).count();
    }
}
