package com.commonbattle.game.social;

/**
 * 读取好友列表快照请求。
 */
public record FriendSnapshotRequest(long playerId) {
    public FriendSnapshotRequest() {
        this(1);
    }

    public FriendSnapshotRequest {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
