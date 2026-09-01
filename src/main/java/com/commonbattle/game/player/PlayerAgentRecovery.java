package com.commonbattle.game.player;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家 Agent 恢复结果。
 * created 表示没有历史快照时创建了新状态，snapshot 表示恢复或创建后的基准快照。
 */
public record PlayerAgentRecovery(PlayerProfile profile, PlayerStateSnapshot snapshot, boolean created) {
    public PlayerAgentRecovery {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(snapshot, "snapshot");
    }

    public static PlayerAgentRecovery createNew(long playerId, Instant now) {
        PlayerProfile profile = new PlayerProfile(playerId, now);
        return new PlayerAgentRecovery(profile, profile.snapshot(0, now), true);
    }

    public static PlayerAgentRecovery restore(PlayerStateSnapshot snapshot) {
        return new PlayerAgentRecovery(PlayerProfile.restore(snapshot), snapshot, false);
    }
}
