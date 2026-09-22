package com.commonbattle.game.player.event;

import java.util.Optional;

/**
 * 玩家领域投影快照读取响应。
 */
public record PlayerDomainProjectionSnapshotResponse(boolean found, PlayerDomainProjectionSnapshot snapshot) {
    public PlayerDomainProjectionSnapshotResponse() {
        this(false, null);
    }

    public static PlayerDomainProjectionSnapshotResponse found(PlayerDomainProjectionSnapshot snapshot) {
        return new PlayerDomainProjectionSnapshotResponse(true, snapshot);
    }

    public static PlayerDomainProjectionSnapshotResponse missing() {
        return new PlayerDomainProjectionSnapshotResponse(false, null);
    }

    public PlayerDomainProjectionSnapshotResponse {
        if (found && snapshot == null) {
            throw new IllegalArgumentException("found snapshot must not be null");
        }
    }

    public Optional<PlayerDomainProjectionSnapshot> optionalSnapshot() {
        return found ? Optional.of(snapshot) : Optional.empty();
    }
}
