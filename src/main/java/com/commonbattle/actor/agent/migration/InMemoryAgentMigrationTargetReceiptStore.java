package com.commonbattle.actor.agent.migration;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存迁入回执存储。
 * 适合单节点测试和本地运行；线上可替换成持久化存储以覆盖目标服重启后的重复 accept。
 */
public final class InMemoryAgentMigrationTargetReceiptStore implements AgentMigrationTargetReceiptStore {
    private final ConcurrentMap<String, AgentMigrationAcceptResponse> receipts = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentMigrationAcceptResponse> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(receipts.get(taskId));
    }

    @Override
    public void save(String taskId, AgentMigrationAcceptResponse response) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        receipts.put(taskId, Objects.requireNonNull(response, "response"));
    }

    public int size() {
        return receipts.size();
    }
}
