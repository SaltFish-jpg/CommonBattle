package com.commonbattle.observability;

/**
 * owner 修复运维操作审计只读视图。
 * HTTP 运维端点和健康指标只读取快照，不参与隔离队列释放逻辑。
 */
public interface OwnerRepairOpsAuditView {
    OwnerRepairOpsAuditPage query(OwnerRepairOpsAuditQuery query);

    OwnerRepairOpsAuditStats stats();
}
