package com.commonbattle.game.social;

/**
 * 好友列表快照仓库。
 * 生产环境可实现为社交 owner DB + Redis 写穿透，Scene/Chat 侧按 owner 事件维护本地只读视图。
 */
public interface FriendSnapshotRepository extends FriendSnapshotReader {
    void save(FriendSnapshot snapshot);
}
