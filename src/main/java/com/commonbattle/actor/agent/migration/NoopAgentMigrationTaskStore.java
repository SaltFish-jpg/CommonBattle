package com.commonbattle.actor.agent.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

enum NoopAgentMigrationTaskStore implements AgentMigrationTaskStore {
    INSTANCE;

    @Override
    public void save(AgentMigrationTask task) {
    }

    @Override
    public Optional<AgentMigrationTask> claim(String taskId, String owner, Instant now, Duration leaseTtl) {
        return Optional.empty();
    }

    @Override
    public void mark(String taskId, AgentMigrationTaskStatus status, String reason, Instant now) {
    }

    @Override
    public List<AgentMigrationTask> pendingTasks() {
        return List.of();
    }

    @Override
    public int purgeTerminalTasksBefore(Instant cutoff) {
        return 0;
    }

    @Override
    public AgentMigrationTaskStoreStats stats(Instant now) {
        return AgentMigrationTaskStoreStats.empty();
    }
}
