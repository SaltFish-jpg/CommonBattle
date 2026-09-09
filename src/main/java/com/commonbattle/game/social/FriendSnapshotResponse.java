package com.commonbattle.game.social;

import java.util.Optional;

/**
 * 好友列表快照读取响应。
 */
public record FriendSnapshotResponse(boolean found, FriendSnapshot snapshot) {
    public FriendSnapshotResponse() {
        this(false, null);
    }

    public static FriendSnapshotResponse found(FriendSnapshot snapshot) {
        return new FriendSnapshotResponse(true, snapshot);
    }

    public static FriendSnapshotResponse missing() {
        return new FriendSnapshotResponse(false, null);
    }

    public FriendSnapshotResponse {
        if (found && snapshot == null) {
            throw new IllegalArgumentException("found snapshot must not be null");
        }
    }

    public Optional<FriendSnapshot> optionalSnapshot() {
        return found ? Optional.of(snapshot) : Optional.empty();
    }
}
