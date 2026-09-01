package com.commonbattle.game.profile;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家基础展示资料快照。
 * 该快照用于跨服展示和本地缓存读取，不作为资产、关系权限等强一致裁决依据。
 */
public record PlayerProfileSnapshot(
        long playerId,
        String name,
        int level,
        AppearanceSummary appearance,
        AllianceBrief alliance,
        FriendBrief friends,
        long revision,
        Instant updatedAt
) {
    public PlayerProfileSnapshot() {
        this(1, "", 1, AppearanceSummary.defaults(), AllianceBrief.none(), new FriendBrief(), 0, Instant.EPOCH);
    }

    public PlayerProfileSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(alliance, "alliance");
        Objects.requireNonNull(friends, "friends");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (level <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
