package com.commonbattle.observability;

import java.time.Instant;
import java.util.Objects;

/**
 * owner 修复运维操作审计记录。
 * 记录人工释放隔离 owner 的动作、目标、释放数量和来源，方便事后排障。
 */
public record OwnerRepairOpsAuditRecord(
        String action,
        String ownerKey,
        int released,
        Instant at,
        String remote,
        String operator
) {
    public OwnerRepairOpsAuditRecord(String action, String ownerKey, int released, Instant at, String remote) {
        this(action, ownerKey, released, at, remote, "");
    }

    public OwnerRepairOpsAuditRecord {
        action = Objects.requireNonNull(action, "action");
        ownerKey = Objects.requireNonNull(ownerKey, "ownerKey");
        at = Objects.requireNonNull(at, "at");
        remote = Objects.requireNonNull(remote, "remote");
        operator = Objects.requireNonNullElse(operator, "").trim();
        if (released < 0) {
            throw new IllegalArgumentException("released must not be negative");
        }
    }
}
