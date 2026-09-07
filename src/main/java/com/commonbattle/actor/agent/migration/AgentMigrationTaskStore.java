package com.commonbattle.actor.agent.migration;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Agent 迁移任务存储。
 * 生产环境应实现为 DB 或 Redis 持久化，重启后用 pendingTasks 恢复未完成迁移。
 */
public interface AgentMigrationTaskStore {
    void save(AgentMigrationTask task);

    Optional<AgentMigrationTask> claim(String taskId, String owner, Instant now, Duration leaseTtl);

    void mark(String taskId, AgentMigrationTaskStatus status, String reason, Instant now);

    List<AgentMigrationTask> pendingTasks();

    int purgeTerminalTasksBefore(Instant cutoff);

    AgentMigrationTaskStoreStats stats(Instant now);

    static AgentMigrationTaskStore none() {
        return NoopAgentMigrationTaskStore.INSTANCE;
    }
}
