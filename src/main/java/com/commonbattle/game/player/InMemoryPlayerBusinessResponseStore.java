package com.commonbattle.game.player;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存完成响应检查点存储，用于测试和本地运行。
 */
public final class InMemoryPlayerBusinessResponseStore implements PlayerBusinessResponseStore {
    private final Map<Long, PlayerBusinessResponseSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public Optional<PlayerBusinessResponseSnapshot> load(long playerId) {
        return Optional.ofNullable(snapshots.get(playerId));
    }

    @Override
    public void save(PlayerBusinessResponseSnapshot snapshot) {
        snapshots.put(snapshot.playerId(), snapshot);
    }
}
