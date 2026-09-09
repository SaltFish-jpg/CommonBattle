package com.commonbattle.game.social;

import java.util.Optional;

/**
 * 玩家好友列表快照读取入口。
 */
public interface FriendSnapshotReader {
    Optional<FriendSnapshot> find(long playerId);
}
