package com.commonbattle.observability;

/**
 * owner 修复运维审计查询条件。
 */
public record OwnerRepairOpsAuditQuery(
        String action,
        String ownerKey,
        int offset,
        int limit
) {
    public OwnerRepairOpsAuditQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
