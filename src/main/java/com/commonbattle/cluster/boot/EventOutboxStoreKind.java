package com.commonbattle.cluster.boot;

/**
 * 启动期版本事件 outbox 存储类型。
 * MEMORY 适合示例和测试；JDBC 适合生产落库并在进程重启后继续补发。
 */
public enum EventOutboxStoreKind {
    MEMORY,
    JDBC
}
