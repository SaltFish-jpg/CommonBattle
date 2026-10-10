package com.commonbattle.game.scene;

import java.util.Objects;

/**
 * 场景联盟成员读取结果。
 * fresh 表示 membership 是至少达到请求 revision 的可信判断；非 fresh 只能用于拒绝或触发修复。
 */
public record SceneAllianceReadResult(
        AllianceMembershipDecision membership,
        SceneAllianceReadStatus status
) {
    public SceneAllianceReadResult {
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(status, "status");
    }

    public boolean fresh() {
        return membership != AllianceMembershipDecision.STALE
                && (status == SceneAllianceReadStatus.LOCAL_HIT || status == SceneAllianceReadStatus.REFRESHED);
    }
}
