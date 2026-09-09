package com.commonbattle.game.social;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版好友快照仓库，用于测试和本地样例。
 */
public final class InMemoryFriendSnapshotRepository implements FriendSnapshotRepository {
    private final Map<Long, FriendSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(FriendSnapshot snapshot) {
        snapshots.put(snapshot.playerId(), snapshot);
    }

    @Override
    public Optional<FriendSnapshot> find(long playerId) {
        return Optional.ofNullable(snapshots.get(playerId));
    }
}
