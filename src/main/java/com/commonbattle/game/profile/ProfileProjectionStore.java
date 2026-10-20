package com.commonbattle.game.profile;

import java.util.List;

/**
 * Profile 本地投影的原子存储。
 * 每条记录同时保存完整展示快照、stale 状态和快照 revision，不能只持久化游标。
 */
public interface ProfileProjectionStore {
    void save(CachedProfile profile);

    List<CachedProfile> loadAll();
}
