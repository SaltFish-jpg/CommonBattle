package com.commonbattle.game.profile;

/**
 * 玩家基础资料快照仓库。
 * 生产环境可实现为 DB + Redis 写穿透，读取方通常优先走本进程 LocalProfileCache。
 */
public interface ProfileSnapshotRepository extends ProfileSnapshotReader {
    void save(PlayerProfileSnapshot snapshot);
}
