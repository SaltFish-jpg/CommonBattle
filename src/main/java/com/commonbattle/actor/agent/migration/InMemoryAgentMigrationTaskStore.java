package com.commonbattle.actor.agent.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存迁移任务存储。
 * 用于测试和单进程样例，线上部署应替换成可跨进程恢复的持久化实现。
 */
public final class InMemoryAgentMigrationTaskStore implements AgentMigrationTaskStore {
    private final ConcurrentMap<String, AgentMigrationTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(AgentMigrationTask task) {
        tasks.put(Objects.requireNonNull(task, "task").taskId(), task);
    }

    @Override
    public Optional<AgentMigrationTask> claim(String taskId, String owner, Instant now, Duration leaseTtl) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        AtomicReference<AgentMigrationTask> claimed = new AtomicReference<>();
        tasks.computeIfPresent(taskId, (ignored, task) -> {
            if (!isPending(task) || !task.leaseAvailable(now)) {
                return task;
            }
            AgentMigrationTask leased = task.withLease(owner, now.plus(leaseTtl), now);
            claimed.set(leased);
            return leased;
        });
        return Optional.ofNullable(claimed.get());
    }

    @Override
    public void mark(String taskId, AgentMigrationTaskStatus status, String reason, Instant now) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        tasks.computeIfPresent(taskId, (ignored, task) -> task.withStatus(status, reason, now));
    }

    @Override
    public List<AgentMigrationTask> pendingTasks() {
        return tasks.values().stream()
                .filter(InMemoryAgentMigrationTaskStore::isPending)
                .toList();
    }

    @Override
    public int purgeTerminalTasksBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        int before = tasks.size();
        tasks.entrySet().removeIf(entry -> isTerminalBefore(entry.getValue(), cutoff));
        return before - tasks.size();
    }

    @Override
    public AgentMigrationTaskStoreStats stats(Instant now) {
        return AgentMigrationTaskStoreStats.from(1, tasks.values(), now);
    }

    private static boolean isPending(AgentMigrationTask task) {
        return task.status() == AgentMigrationTaskStatus.PREPARED
                || task.status() == AgentMigrationTaskStatus.MOVED;
    }

    private static boolean isTerminalBefore(AgentMigrationTask task, Instant cutoff) {
        return !isPending(task) && task.updatedAt().isBefore(cutoff);
    }
}
