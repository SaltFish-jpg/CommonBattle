package com.commonbattle.observability;

/**
 * owner 修复运维审计聚合健康统计。
 */
public record OwnerRepairOpsAuditHealthStats(
        int auditCount,
        int retainedEntries,
        long recordedEntries,
        long releaseOps,
        long releaseAllOps,
        long releasedOwners,
        long notFoundReleaseOps,
        long droppedEntries
) {
    public static OwnerRepairOpsAuditHealthStats empty() {
        return new OwnerRepairOpsAuditHealthStats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static OwnerRepairOpsAuditHealthStats from(
            int auditCount,
            int retainedEntries,
            long recordedEntries,
            long releaseOps,
            long releaseAllOps,
            long releasedOwners,
            long notFoundReleaseOps,
            long droppedEntries
    ) {
        return new OwnerRepairOpsAuditHealthStats(auditCount, retainedEntries, recordedEntries, releaseOps,
                releaseAllOps, releasedOwners, notFoundReleaseOps, droppedEntries);
    }
}
