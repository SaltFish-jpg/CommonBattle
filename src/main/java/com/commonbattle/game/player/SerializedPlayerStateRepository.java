package com.commonbattle.game.player;

import com.commonbattle.persistence.AtomicBytesStore;

import java.util.Objects;
import java.util.Optional;

/**
 * 基于字节存储的玩家状态仓库。
 * 字节存储可替换为 Redis、DB 或本地 WAL，仓库只负责 key 规划和快照序列化。
 */
public final class SerializedPlayerStateRepository implements PlayerStateRepository {
    private static final String PREFIX = "player:state:";

    private final AtomicBytesStore store;
    private final PlayerStateSnapshotSerializer serializer;

    public SerializedPlayerStateRepository(AtomicBytesStore store, PlayerStateSnapshotSerializer serializer) {
        this.store = Objects.requireNonNull(store, "store");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public Optional<PlayerStateSnapshot> load(Long key) {
        Objects.requireNonNull(key, "key");
        return store.load(key(key)).map(serializer::deserialize);
    }

    @Override
    public void save(Long key, PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        if (key.longValue() != snapshot.playerId()) {
            throw new IllegalArgumentException("repository key must match snapshot playerId");
        }
        store.put(key(key), serializer.serialize(snapshot));
    }

    private String key(long playerId) {
        return PREFIX + playerId;
    }
}
