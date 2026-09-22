package com.commonbattle.observability;

import java.util.List;

/**
 * owner 修复运维审计分页结果。
 */
public record OwnerRepairOpsAuditPage(
        List<OwnerRepairOpsAuditRecord> entries,
        int matched,
        int offset,
        int limit
) {
    public OwnerRepairOpsAuditPage {
        entries = List.copyOf(entries);
        if (matched < 0) {
            throw new IllegalArgumentException("matched must not be negative");
        }
    }
}
