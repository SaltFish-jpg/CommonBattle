package com.commonbattle.game.social;

/**
 * 读取联盟快照请求。
 */
public record AllianceSnapshotRequest(long allianceId) {
    public AllianceSnapshotRequest() {
        this(1);
    }

    public AllianceSnapshotRequest {
        if (allianceId <= 0) {
            throw new IllegalArgumentException("allianceId must be positive");
        }
    }
}
