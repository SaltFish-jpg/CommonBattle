package com.commonbattle.actor.agent.migration;

/**
 * Agent 迁移任务的稳定序列化边界。
 * 持久化实现可用它把迁移日志写入 DB、Redis 或本地 WAL，避免依赖 Java 对象序列化。
 */
public interface AgentMigrationTaskSerializer {
    byte[] encode(AgentMigrationTask task);

    AgentMigrationTask decode(byte[] bytes);
}
