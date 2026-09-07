package com.commonbattle.cluster.boot;

/**
 * 启动期迁移任务存储类型。
 * MEMORY 适合示例和测试，FILE 适合单节点 WAL 演练；分布式生产环境应扩展 Redis 或 SQL 实现。
 */
public enum AgentMigrationTaskStoreKind {
    MEMORY,
    FILE,
    JDBC
}
