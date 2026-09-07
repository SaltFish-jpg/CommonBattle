package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移协调策略。
 */
public record AgentMigrationPolicy(int targetAcceptAttempts) {
    public AgentMigrationPolicy {
        if (targetAcceptAttempts <= 0) {
            throw new IllegalArgumentException("targetAcceptAttempts must be positive");
        }
    }

    public static AgentMigrationPolicy defaults() {
        return new AgentMigrationPolicy(1);
    }
}
