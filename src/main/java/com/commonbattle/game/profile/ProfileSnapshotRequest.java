package com.commonbattle.game.profile;

/**
 * 读取玩家基础资料快照请求。
 */
public record ProfileSnapshotRequest(long playerId) {
    public ProfileSnapshotRequest() {
        this(1);
    }

    public ProfileSnapshotRequest {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
