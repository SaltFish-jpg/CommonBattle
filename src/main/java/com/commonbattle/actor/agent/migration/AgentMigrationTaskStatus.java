package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移任务持久化状态。
 */
public enum AgentMigrationTaskStatus {
    PREPARED,
    MOVED,
    TARGET_ACCEPTED,
    ROLLED_BACK,
    ROLLBACK_FAILED
}
