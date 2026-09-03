package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * 源 Agent 迁移提交结果。
 * 监听方可据此在目录 move 成功后通知目标服务接收迁移快照。
 */
public record AgentMigrationCompletion(
        AgentIdentity identity,
        AgentLocation source,
        AgentLocation target,
        boolean moved,
        Throwable failure
) {
    public AgentMigrationCompletion {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }

    public Optional<Throwable> failedCause() {
        return Optional.ofNullable(failure);
    }
}
