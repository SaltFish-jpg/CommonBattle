package com.commonbattle.game.event;

import java.util.List;
import java.util.Optional;

/**
 * 版本事件 inbox 的字节级存储。
 * insertIfAbsent 是消费端幂等屏障，必须对同一 storageKey 原子生效。
 */
public interface VersionedEventInboxBytesStore {
    boolean insertIfAbsent(String storageKey, byte[] bytes);

    void save(String storageKey, byte[] bytes);

    Optional<byte[]> load(String storageKey);

    void delete(String storageKey);

    List<byte[]> loadAll();
}
