package com.commonbattle.game.scene;

import java.util.Objects;

/**
 * 玩家在场景或频道内的一份可见性兴趣。
 * interestKey 可表示场景、切块、副本房间或聊天频道；allianceId 为 0 表示当前不关注联盟成员视图。
 */
public record ScenePlayerInterest(
        long playerId,
        String interestKey,
        long allianceId
) {
    public static final long NO_ALLIANCE = 0;

    public ScenePlayerInterest {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        Objects.requireNonNull(interestKey, "interestKey");
        if (interestKey.isBlank()) {
            throw new IllegalArgumentException("interestKey must not be blank");
        }
        if (allianceId < 0) {
            throw new IllegalArgumentException("allianceId must not be negative");
        }
    }

    public static ScenePlayerInterest of(long playerId, String interestKey) {
        return new ScenePlayerInterest(playerId, interestKey, NO_ALLIANCE);
    }

    public static ScenePlayerInterest withAlliance(long playerId, String interestKey, long allianceId) {
        return new ScenePlayerInterest(playerId, interestKey, allianceId);
    }

    public boolean hasAlliance() {
        return allianceId > 0;
    }
}
