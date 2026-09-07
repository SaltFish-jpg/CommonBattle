package com.commonbattle.game.event;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版事件 outbox 字节存储。
 * 适合测试和本地样例，线上应替换为与玩家状态同事务提交的持久化实现。
 */
public final class InMemoryVersionedEventOutboxBytesStore implements VersionedEventOutboxBytesStore {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<Long, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public void save(long outboxId, byte[] bytes) {
        records.put(requireOutboxId(outboxId), copy(bytes));
    }

    @Override
    public Optional<byte[]> load(long outboxId) {
        byte[] bytes = records.get(requireOutboxId(outboxId));
        return bytes == null ? Optional.empty() : Optional.of(copy(bytes));
    }

    @Override
    public void delete(long outboxId) {
        records.remove(requireOutboxId(outboxId));
    }

    @Override
    public List<byte[]> loadAll() {
        return records.values().stream()
                .map(InMemoryVersionedEventOutboxBytesStore::copy)
                .toList();
    }

    private static long requireOutboxId(long outboxId) {
        if (outboxId <= 0) {
            throw new IllegalArgumentException("outboxId must be positive");
        }
        return outboxId;
    }

    private static byte[] copy(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length);
    }
}
