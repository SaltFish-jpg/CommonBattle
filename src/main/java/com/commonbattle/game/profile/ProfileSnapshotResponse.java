package com.commonbattle.game.profile;

import java.util.Optional;

/**
 * 玩家基础资料快照读取响应。
 */
public record ProfileSnapshotResponse(boolean found, PlayerProfileSnapshot snapshot) {
    public ProfileSnapshotResponse() {
        this(false, null);
    }

    public static ProfileSnapshotResponse found(PlayerProfileSnapshot snapshot) {
        return new ProfileSnapshotResponse(true, snapshot);
    }

    public static ProfileSnapshotResponse missing() {
        return new ProfileSnapshotResponse(false, null);
    }

    public ProfileSnapshotResponse {
        if (found && snapshot == null) {
            throw new IllegalArgumentException("found snapshot must not be null");
        }
    }

    public Optional<PlayerProfileSnapshot> optionalSnapshot() {
        return found ? Optional.of(snapshot) : Optional.empty();
    }
}
