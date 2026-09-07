package com.commonbattle.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内存原子字节存储。
 * 用于单元测试和样例部署；线上应替换成 Redis Lua、DB 乐观锁或一致性 KV。
 */
public final class InMemoryAtomicBytesStore implements AtomicBytesStore {
    private final ConcurrentMap<String, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public Optional<byte[]> load(String key) {
        byte[] bytes = records.get(requireKey(key));
        return bytes == null ? Optional.empty() : Optional.of(copy(bytes));
    }

    @Override
    public void put(String key, byte[] bytes) {
        records.put(requireKey(key), copy(bytes));
    }

    @Override
    public boolean putIfAbsent(String key, byte[] bytes) {
        return records.putIfAbsent(requireKey(key), copy(bytes)) == null;
    }

    @Override
    public boolean compareAndSet(String key, byte[] expected, byte[] updated) {
        Objects.requireNonNull(expected, "expected");
        byte[] next = copy(updated);
        AtomicBoolean changed = new AtomicBoolean(false);
        records.computeIfPresent(requireKey(key), (ignored, current) -> {
            if (!Arrays.equals(current, expected)) {
                return current;
            }
            changed.set(true);
            return next;
        });
        return changed.get();
    }

    @Override
    public boolean compareAndDelete(String key, byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        AtomicBoolean deleted = new AtomicBoolean(false);
        records.computeIfPresent(requireKey(key), (ignored, current) -> {
            if (!Arrays.equals(current, expected)) {
                return current;
            }
            deleted.set(true);
            return null;
        });
        return deleted.get();
    }

    @Override
    public List<Entry> scanPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return records.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> new Entry(entry.getKey(), copy(entry.getValue())))
                .toList();
    }

    public int size() {
        return records.size();
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return key;
    }

    private static byte[] copy(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return Arrays.copyOf(bytes, bytes.length);
    }
}
