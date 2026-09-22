package com.commonbattle.observability;

/**
 * owner 修复运维操作审计落点。
 * 生产环境可实现为本地日志、集中审计库或运维事件流。
 */
@FunctionalInterface
public interface OwnerRepairOpsAuditSink {
    OwnerRepairOpsAuditSink NOOP = record -> {
    };

    void record(OwnerRepairOpsAuditRecord record);
}
