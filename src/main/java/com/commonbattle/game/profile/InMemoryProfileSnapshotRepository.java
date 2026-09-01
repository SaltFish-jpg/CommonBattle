package com.commonbattle.game.profile;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版玩家资料快照仓库，用于测试和本地样例。
 */
public final class InMemoryProfileSnapshotRepository implements ProfileSnapshotRepository {
    private final Map<Long, PlayerProfileSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(PlayerProfileSnapshot snapshot) {
        snapshots.put(snapshot.playerId(), snapshot);
    }

    @Override
    public Optional<PlayerProfileSnapshot> find(long playerId) {
        return Optional.ofNullable(snapshots.get(playerId));
    }
}
