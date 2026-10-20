package com.commonbattle.cluster.boot;

/**
 * Center 商店库存存储类型。
 * ATOMIC_MEMORY 通过通用原子字节存储验证 CAS 形态的替换边界，JDBC 用于生产持久化。
 */
public enum ShopStockStoreKind {
    MEMORY,
    ATOMIC_MEMORY,
    JDBC
}
