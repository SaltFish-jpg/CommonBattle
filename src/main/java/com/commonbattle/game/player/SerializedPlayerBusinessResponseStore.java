package com.commonbattle.game.player;

import com.commonbattle.persistence.AtomicBytesStore;

import java.util.Objects;
import java.util.Optional;

/**
 * 基于原子字节存储的完成响应检查点仓库。
 * 生产环境可将底层替换为 Redis、DB 或 WAL；单个玩家快照始终以一个 key 原子覆盖。
 */
public final class SerializedPlayerBusinessResponseStore implements PlayerBusinessResponseStore {
    private static final String PREFIX = "player:business-responses:";

    private final AtomicBytesStore store;
    private final PlayerBusinessResponseSnapshotCodec codec;

    public SerializedPlayerBusinessResponseStore(
            AtomicBytesStore store,
            PlayerBusinessResponseSnapshotCodec codec
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Optional<PlayerBusinessResponseSnapshot> load(long playerId) {
        return store.load(key(playerId)).map(codec::decode);
    }

    @Override
    public void save(PlayerBusinessResponseSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        store.put(key(snapshot.playerId()), codec.encode(snapshot));
    }

    private static String key(long playerId) {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        return PREFIX + playerId;
    }
}
