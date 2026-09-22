package com.commonbattle.game.player.event;

/**
 * 读取玩家领域投影快照请求。
 */
public record PlayerDomainProjectionSnapshotRequest(long playerId) {
    public PlayerDomainProjectionSnapshotRequest() {
        this(1);
    }

    public PlayerDomainProjectionSnapshotRequest {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
    }
}
