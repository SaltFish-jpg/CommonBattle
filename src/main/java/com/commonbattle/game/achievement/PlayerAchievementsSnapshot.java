package com.commonbattle.game.achievement;

import java.util.Map;

/**
 * 玩家成就状态快照。
 */
public record PlayerAchievementsSnapshot(Map<String, AchievementProgressSnapshot> progress) {
    public PlayerAchievementsSnapshot {
        progress = Map.copyOf(progress);
    }

    public static PlayerAchievementsSnapshot empty() {
        return new PlayerAchievementsSnapshot(Map.of());
    }
}
