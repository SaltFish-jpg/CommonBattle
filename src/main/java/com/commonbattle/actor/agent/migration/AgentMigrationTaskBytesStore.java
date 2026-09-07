package com.commonbattle.actor.agent.migration;

import java.util.List;
import java.util.Optional;

/**
 * Agent 迁移任务的字节级存储。
 * DB、Redis 或 WAL 实现应在 compareAndSet 中提供原子更新，保证恢复任务租约不会被多个进程同时抢到。
 */
public interface AgentMigrationTaskBytesStore {
    void save(String taskId, byte[] bytes);

    Optional<byte[]> load(String taskId);

    boolean compareAndSet(String taskId, byte[] expected, byte[] updated);

    boolean compareAndDelete(String taskId, byte[] expected);

    List<byte[]> loadAll();
}
