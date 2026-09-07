package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.time.Instant;
import java.util.Objects;

/**
 * Agent 迁移恢复任务。
 * PREPARED 表示源邮箱已经完成快照打包，MOVED 表示目录已经指向目标但目标 Actor 恢复尚未确认。
 */
public record AgentMigrationTask(
        String taskId,
        AgentIdentity identity,
        AgentLocation source,
        AgentLocation target,
        AgentMigrationSnapshot snapshot,
        AgentMigrationTaskStatus status,
        String reason,
        Instant updatedAt,
        String leaseOwner,
        Instant leaseExpiresAt
) {
    public AgentMigrationTask(
            String taskId,
            AgentIdentity identity,
            AgentLocation source,
            AgentLocation target,
            AgentMigrationSnapshot snapshot,
            AgentMigrationTaskStatus status,
            String reason,
            Instant updatedAt
    ) {
        this(taskId, identity, source, target, snapshot, status, reason, updatedAt, "", Instant.EPOCH);
    }

    public AgentMigrationTask {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        reason = Objects.requireNonNullElse(reason, "");
        leaseOwner = Objects.requireNonNullElse(leaseOwner, "");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }

    public AgentMigrationTask withStatus(AgentMigrationTaskStatus nextStatus, String nextReason, Instant now) {
        return new AgentMigrationTask(taskId, identity, source, target, snapshot, nextStatus, nextReason, now, "",
                Instant.EPOCH);
    }

    public AgentMigrationTask withLease(String owner, Instant expiresAt, Instant now) {
        return new AgentMigrationTask(taskId, identity, source, target, snapshot, status, reason, now, owner, expiresAt);
    }

    public boolean leaseAvailable(Instant now) {
        return leaseOwner.isBlank() || !leaseExpiresAt.isAfter(now);
    }
}
