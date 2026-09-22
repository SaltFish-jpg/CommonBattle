package com.commonbattle.observability;

/**
 * owner 修复运维操作审计聚合统计。
 */
public record OwnerRepairOpsAuditStats(
        int retainedEntries,
        long recordedEntries,
        long releaseOps,
        long releaseAllOps,
        long releasedOwners,
        long notFoundReleaseOps,
        long droppedEntries
) {
    public static OwnerRepairOpsAuditStats empty() {
        return new OwnerRepairOpsAuditStats(0, 0, 0, 0, 0, 0, 0);
    }
}
