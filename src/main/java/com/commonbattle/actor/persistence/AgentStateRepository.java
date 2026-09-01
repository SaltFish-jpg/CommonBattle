package com.commonbattle.actor.persistence;

import java.util.Optional;

/**
 * Agent 状态仓库。
 * 生产环境可由 DB、Redis 或冷热分层存储实现；Actor 层只依赖加载和保存快照语义。
 */
public interface AgentStateRepository<K, S> {
    Optional<S> load(K key);

    void save(K key, S snapshot);
}
