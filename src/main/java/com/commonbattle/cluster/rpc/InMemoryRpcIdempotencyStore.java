package com.commonbattle.cluster.rpc;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 有容量上限的内存 RPC 幂等结果存储。
 * 适合单进程开发和测试；跨版本灰度或服务重启后的去重应替换为共享持久化实现。
 */
public final class InMemoryRpcIdempotencyStore implements RpcIdempotencyStore {
    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<RpcIdempotencyKey, Entry> cache;

    public InMemoryRpcIdempotencyStore(int capacity) {
        this(capacity, Duration.ZERO, Clock.systemUTC());
    }

    public InMemoryRpcIdempotencyStore(int capacity, Duration ttl, Clock clock) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<RpcIdempotencyKey, Entry> eldest) {
                return capacity > 0 && size() > capacity;
            }
        };
    }

    @Override
    public synchronized RpcIdempotencyResult get(RpcIdempotencyKey key) {
        Entry entry = cache.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return null;
        }
        if (expired(entry)) {
            cache.remove(key);
            return null;
        }
        return entry.result();
    }

    @Override
    public synchronized void put(RpcIdempotencyKey key, RpcIdempotencyResult result) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(result, "result");
        if (capacityDisabled()) {
            return;
        }
        purgeExpired();
        cache.put(key, new Entry(result, clock.millis()));
    }

    @Override
    public synchronized int size() {
        purgeExpired();
        return cache.size();
    }

    private boolean capacityDisabled() {
        return capacity == 0;
    }

    private void purgeExpired() {
        if (ttl.isZero()) {
            return;
        }
        cache.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    private boolean expired(Entry entry) {
        return !ttl.isZero() && clock.millis() - entry.storedAtMillis() >= ttl.toMillis();
    }

    private record Entry(RpcIdempotencyResult result, long storedAtMillis) {
    }
}
