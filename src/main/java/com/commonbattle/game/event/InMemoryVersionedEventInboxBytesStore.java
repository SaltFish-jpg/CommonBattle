package com.commonbattle.game.event;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存版 inbox 字节存储。
 */
public final class InMemoryVersionedEventInboxBytesStore implements VersionedEventInboxBytesStore {
    private final ConcurrentMap<String, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public boolean insertIfAbsent(String storageKey, byte[] bytes) {
        return records.putIfAbsent(requireKey(storageKey), copy(bytes)) == null;
    }

    @Override
    public void save(String storageKey, byte[] bytes) {
        records.put(requireKey(storageKey), copy(bytes));
    }

    @Override
    public Optional<byte[]> load(String storageKey) {
        byte[] bytes = records.get(requireKey(storageKey));
        return bytes == null ? Optional.empty() : Optional.of(copy(bytes));
    }

    @Override
    public void delete(String storageKey) {
        records.remove(requireKey(storageKey));
    }

    @Override
    public List<byte[]> loadAll() {
        return records.values().stream().map(InMemoryVersionedEventInboxBytesStore::copy).toList();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        return key;
    }

    private static byte[] copy(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
