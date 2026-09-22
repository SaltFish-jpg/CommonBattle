package com.commonbattle.observability;

/**
 * owner 修复运维审计配置。
 */
public record OwnerRepairOpsAuditConfig(
        int capacity,
        boolean recordNotFoundRelease
) {
    public static final int DEFAULT_CAPACITY = 128;

    public OwnerRepairOpsAuditConfig {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }

    public static OwnerRepairOpsAuditConfig defaults() {
        return new OwnerRepairOpsAuditConfig(DEFAULT_CAPACITY, true);
    }
}
