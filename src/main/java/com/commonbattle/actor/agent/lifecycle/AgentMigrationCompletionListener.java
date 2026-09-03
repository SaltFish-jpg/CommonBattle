package com.commonbattle.actor.agent.lifecycle;

/**
 * 源 Agent 迁移提交监听器。
 * 回调发生在源 Agent 邮箱内，耗时的跨服通知应转交给外部执行器。
 */
@FunctionalInterface
public interface AgentMigrationCompletionListener {
    void completed(AgentMigrationCompletion completion);

    static AgentMigrationCompletionListener ignore() {
        return ignored -> {
        };
    }
}
