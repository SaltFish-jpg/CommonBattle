package com.commonbattle.actor.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 Agent 状态仓库。
 * 用于测试、迁移沙盘和单进程样例，不提供进程崩溃后的持久化保证。
 */
public final class InMemoryAgentStateRepository<K, S> implements AgentStateRepository<K, S> {
    private final Map<K, S> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<S> load(K key) {
        return Optional.ofNullable(snapshots.get(key));
    }

    @Override
    public void save(K key, S snapshot) {
        snapshots.put(key, snapshot);
    }
}
