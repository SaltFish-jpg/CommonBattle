package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.util.Objects;

/**
 * Agent 迁移最终结果。
 * 业务可根据 status 做补偿、告警或向玩家返回可重试错误。
 */
public record AgentMigrationResult(
        AgentIdentity identity,
        AgentLocation source,
        AgentLocation target,
        AgentMigrationResultStatus status,
        String reason
) {
    public AgentMigrationResult {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(status, "status");
        reason = Objects.requireNonNullElse(reason, "");
    }
}
