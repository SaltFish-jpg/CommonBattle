package com.commonbattle.game.social;

import java.util.Set;

/**
 * 玩家好友列表只读快照。
 */
public record FriendSnapshot(long playerId, long revision, Set<Long> friends) {
    public FriendSnapshot {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        friends = Set.copyOf(friends);
        if (friends.contains(playerId)) {
            throw new IllegalArgumentException("friends must not contain self");
        }
    }

    public String ownerKey() {
        return FriendOwnerKeyParser.ownerKey(playerId);
    }
}
