package com.commonbattle.game.social;

import java.util.Optional;

/**
 * 联盟快照读取响应。
 */
public record AllianceSnapshotResponse(boolean found, AllianceSnapshot snapshot) {
    public AllianceSnapshotResponse() {
        this(false, null);
    }

    public static AllianceSnapshotResponse found(AllianceSnapshot snapshot) {
        return new AllianceSnapshotResponse(true, snapshot);
    }

    public static AllianceSnapshotResponse missing() {
        return new AllianceSnapshotResponse(false, null);
    }

    public AllianceSnapshotResponse {
        if (found && snapshot == null) {
            throw new IllegalArgumentException("found snapshot must not be null");
        }
    }

    public Optional<AllianceSnapshot> optionalSnapshot() {
        return found ? Optional.of(snapshot) : Optional.empty();
    }
}
