package com.commonbattle.actor.agent.migration;

import java.util.Objects;

/**
 * 目标服务接收迁移 Agent 的结果。
 */
public record AgentMigrationAcceptResponse(boolean accepted, String reason) {
    public AgentMigrationAcceptResponse {
        reason = Objects.requireNonNullElse(reason, "");
    }

    public static AgentMigrationAcceptResponse success() {
        return new AgentMigrationAcceptResponse(true, "");
    }

    public static AgentMigrationAcceptResponse rejected(String reason) {
        return new AgentMigrationAcceptResponse(false, reason);
    }
}
