package com.commonbattle.game.profile;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Profile 投影字节存储的内存实现。
 */
public final class InMemoryProfileProjectionBytesStore implements ProfileProjectionBytesStore {
    private final ConcurrentMap<String, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public void save(String key, byte[] payload) {
        records.put(key, Arrays.copyOf(payload, payload.length));
    }

    @Override
    public List<byte[]> loadAll() {
        return records.values().stream().map(bytes -> Arrays.copyOf(bytes, bytes.length)).toList();
    }
}
