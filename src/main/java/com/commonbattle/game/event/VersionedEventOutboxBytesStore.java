package com.commonbattle.game.event;

import java.util.List;
import java.util.Optional;

/**
 * 版本事件 outbox 的字节级存储。
 * 生产实现可落 SQL、Redis Stream 或 WAL；nextId 必须在同一存储实例内单调递增。
 */
public interface VersionedEventOutboxBytesStore {
    long nextId();

    void save(long outboxId, byte[] bytes);

    Optional<byte[]> load(long outboxId);

    void delete(long outboxId);

    List<byte[]> loadAll();
}
