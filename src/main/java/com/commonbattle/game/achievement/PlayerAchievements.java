package com.commonbattle.game.achievement;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家成就状态。
 * 该对象只应在玩家 Agent 邮箱内修改。
 */
public final class PlayerAchievements {
    private final Map<String, AchievementProgress> progress = new HashMap<>();

    public AchievementProgress progress(String achievementId) {
        return progress.computeIfAbsent(achievementId, AchievementProgress::new);
    }

    public PlayerAchievementsSnapshot snapshot() {
        Map<String, AchievementProgressSnapshot> snapshots = new HashMap<>();
        progress.forEach((achievementId, achievementProgress) ->
                snapshots.put(achievementId, achievementProgress.snapshot()));
        return new PlayerAchievementsSnapshot(snapshots);
    }

    public void restore(PlayerAchievementsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        progress.clear();
        snapshot.progress().forEach((achievementId, saved) -> {
            AchievementProgress restored = new AchievementProgress(achievementId);
            restored.restore(saved);
            progress.put(achievementId, restored);
        });
    }
}
