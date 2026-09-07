package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移最终结果回调。
 * 回调由迁移协调器的完成执行器触发，不在源 Agent 邮箱内执行业务补偿。
 */
@FunctionalInterface
public interface AgentMigrationResultCallback {
    void completed(AgentMigrationResult result);

    static AgentMigrationResultCallback ignore() {
        return ignored -> {
        };
    }
}
