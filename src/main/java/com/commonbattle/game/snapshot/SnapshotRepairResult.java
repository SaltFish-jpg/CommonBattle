package com.commonbattle.game.snapshot;

/**
 * 单个 owner 快照修补结果。
 */
public record SnapshotRepairResult(
        String ownerKey,
        SnapshotRepairStatus status,
        long revision,
        String message
) {
    public static SnapshotRepairResult refreshed(String ownerKey, long revision) {
        return new SnapshotRepairResult(ownerKey, SnapshotRepairStatus.REFRESHED, revision, "");
    }

    public static SnapshotRepairResult missing(String ownerKey) {
        return new SnapshotRepairResult(ownerKey, SnapshotRepairStatus.MISSING, 0, "");
    }

    public static SnapshotRepairResult invalidOwnerKey(String ownerKey) {
        return new SnapshotRepairResult(ownerKey, SnapshotRepairStatus.INVALID_OWNER_KEY, 0, "");
    }

    public static SnapshotRepairResult failed(String ownerKey, String message) {
        return new SnapshotRepairResult(ownerKey, SnapshotRepairStatus.FAILED, 0, message == null ? "" : message);
    }
}
