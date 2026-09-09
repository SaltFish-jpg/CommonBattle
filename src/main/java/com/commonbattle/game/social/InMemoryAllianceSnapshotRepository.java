package com.commonbattle.game.social;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版联盟快照仓库，用于测试和本地样例。
 */
public final class InMemoryAllianceSnapshotRepository implements AllianceSnapshotRepository {
    private final Map<Long, AllianceSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(AllianceSnapshot snapshot) {
        snapshots.put(snapshot.allianceId(), snapshot);
    }

    @Override
    public Optional<AllianceSnapshot> find(long allianceId) {
        return Optional.ofNullable(snapshots.get(allianceId));
    }
}
