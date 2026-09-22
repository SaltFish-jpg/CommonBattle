package com.commonbattle.actor.agent.migration;

import java.time.Instant;
import java.util.Objects;

/**
 * 目标服迁入回执记录。
 * 记录 taskId 和最终响应，用于目标服重启后继续识别源服重试的同一次迁移。
 */
public record AgentMigrationTargetReceipt(
        String taskId,
        AgentMigrationAcceptResponse response,
        Instant updatedAt
) {
    public AgentMigrationTargetReceipt {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
    }
}
