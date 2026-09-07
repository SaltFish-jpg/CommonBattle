package com.commonbattle.persistence;

import java.util.List;
import java.util.Optional;

/**
 * 原子字节存储契约。
 * Redis、DB 或 WAL 实现需要保证 putIfAbsent、compareAndSet、compareAndDelete 在单 key 上原子执行。
 */
public interface AtomicBytesStore {
    Optional<byte[]> load(String key);

    void put(String key, byte[] bytes);

    boolean putIfAbsent(String key, byte[] bytes);

    boolean compareAndSet(String key, byte[] expected, byte[] updated);

    boolean compareAndDelete(String key, byte[] expected);

    List<Entry> scanPrefix(String prefix);

    record Entry(String key, byte[] bytes) {
    }
}
