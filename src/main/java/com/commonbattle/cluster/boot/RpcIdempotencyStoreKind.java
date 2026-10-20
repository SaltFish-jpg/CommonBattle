package com.commonbattle.cluster.boot;

/**
 * RPC 服务端幂等状态的部署存储类型。
 * MEMORY 适合本地开发；JDBC 通过共享单键 CAS 协调多个服务实例。
 */
public enum RpcIdempotencyStoreKind {
    MEMORY,
    JDBC
}
