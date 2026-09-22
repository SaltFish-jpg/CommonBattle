package com.commonbattle.actor.agent.migration;

import java.util.Optional;

/**
 * 目标服迁入接收回执存储。
 * 源服超时重试或恢复服务重放同一个迁移任务时，目标服依靠它避免重复恢复同一个 Agent。
 */
public interface AgentMigrationTargetReceiptStore {
    Optional<AgentMigrationAcceptResponse> find(String taskId);

    void save(String taskId, AgentMigrationAcceptResponse response);

    static AgentMigrationTargetReceiptStore none() {
        return NoopAgentMigrationTargetReceiptStore.INSTANCE;
    }
}
