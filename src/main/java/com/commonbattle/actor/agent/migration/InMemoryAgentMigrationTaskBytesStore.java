package com.commonbattle.actor.agent.migration;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存字节级迁移任务存储。
 * 用于验证持久化协议和 CAS 语义，线上可替换为 Redis Lua、SQL version 字段或外部 WAL 实现。
 */
public final class InMemoryAgentMigrationTaskBytesStore implements AgentMigrationTaskBytesStore {
    private final ConcurrentMap<String, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public void save(String taskId, byte[] bytes) {
        records.put(requireTaskId(taskId), copy(bytes));
    }

    @Override
    public Optional<byte[]> load(String taskId) {
        byte[] bytes = records.get(requireTaskId(taskId));
        return bytes == null ? Optional.empty() : Optional.of(copy(bytes));
    }

    @Override
    public boolean compareAndSet(String taskId, byte[] expected, byte[] updated) {
        Objects.requireNonNull(expected, "expected");
        byte[] next = copy(updated);
        AtomicBoolean changed = new AtomicBoolean(false);
        records.computeIfPresent(requireTaskId(taskId), (ignored, current) -> {
            if (!Arrays.equals(current, expected)) {
                return current;
            }
            changed.set(true);
            return next;
        });
        return changed.get();
    }

    @Override
    public boolean compareAndDelete(String taskId, byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        AtomicBoolean deleted = new AtomicBoolean(false);
        records.computeIfPresent(requireTaskId(taskId), (ignored, current) -> {
            if (!Arrays.equals(current, expected)) {
                return current;
            }
            deleted.set(true);
            return null;
        });
        return deleted.get();
    }

    @Override
    public List<byte[]> loadAll() {
        return records.values().stream()
                .map(InMemoryAgentMigrationTaskBytesStore::copy)
                .toList();
    }

    private static String requireTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        return taskId;
    }

    private static byte[] copy(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return Arrays.copyOf(bytes, bytes.length);
    }
}
