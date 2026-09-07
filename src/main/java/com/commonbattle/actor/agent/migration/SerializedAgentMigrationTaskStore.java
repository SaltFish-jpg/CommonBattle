package com.commonbattle.actor.agent.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于字节存储的迁移任务存储适配器。
 * 协调器继续使用对象级接口，底层可替换为 Redis、DB 或 WAL，从而固定迁移日志的持久化边界。
 */
public final class SerializedAgentMigrationTaskStore implements AgentMigrationTaskStore {
    private final AgentMigrationTaskBytesStore bytesStore;
    private final AgentMigrationTaskSerializer serializer;

    public SerializedAgentMigrationTaskStore(
            AgentMigrationTaskBytesStore bytesStore,
            AgentMigrationTaskSerializer serializer
    ) {
        this.bytesStore = Objects.requireNonNull(bytesStore, "bytesStore");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public void save(AgentMigrationTask task) {
        Objects.requireNonNull(task, "task");
        bytesStore.save(task.taskId(), serializer.encode(task));
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
        while (true) {
            Optional<byte[]> current = bytesStore.load(taskId);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            AgentMigrationTask task = serializer.decode(current.get());
            if (!isPending(task) || !task.leaseAvailable(now)) {
                return Optional.empty();
            }
            AgentMigrationTask leased = task.withLease(owner, now.plus(leaseTtl), now);
            if (bytesStore.compareAndSet(taskId, current.get(), serializer.encode(leased))) {
                return Optional.of(leased);
            }
        }
    }

    @Override
    public void mark(String taskId, AgentMigrationTaskStatus status, String reason, Instant now) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        while (true) {
            Optional<byte[]> current = bytesStore.load(taskId);
            if (current.isEmpty()) {
                return;
            }
            AgentMigrationTask task = serializer.decode(current.get());
            AgentMigrationTask marked = task.withStatus(status, reason, now);
            if (bytesStore.compareAndSet(taskId, current.get(), serializer.encode(marked))) {
                return;
            }
        }
    }

    @Override
    public List<AgentMigrationTask> pendingTasks() {
        return bytesStore.loadAll().stream()
                .map(serializer::decode)
                .filter(SerializedAgentMigrationTaskStore::isPending)
                .toList();
    }

    @Override
    public int purgeTerminalTasksBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        int purged = 0;
        for (byte[] bytes : bytesStore.loadAll()) {
            AgentMigrationTask task = serializer.decode(bytes);
            if (!isPending(task) && task.updatedAt().isBefore(cutoff)
                    && bytesStore.compareAndDelete(task.taskId(), bytes)) {
                purged++;
            }
        }
        return purged;
    }

    @Override
    public AgentMigrationTaskStoreStats stats(Instant now) {
        Objects.requireNonNull(now, "now");
        return AgentMigrationTaskStoreStats.from(1, bytesStore.loadAll().stream()
                .map(serializer::decode)
                .toList(), now);
    }

    private static boolean isPending(AgentMigrationTask task) {
        return task.status() == AgentMigrationTaskStatus.PREPARED
                || task.status() == AgentMigrationTaskStatus.MOVED;
    }
}
