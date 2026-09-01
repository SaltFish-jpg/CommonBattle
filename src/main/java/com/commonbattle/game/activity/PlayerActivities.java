package com.commonbattle.game.activity;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家活动状态集合。
 */
public final class PlayerActivities {
    private final Map<String, ActivityProgress> progress = new HashMap<>();

    public ActivityProgress progress(String activityId) {
        return progress.computeIfAbsent(activityId, ignored -> new ActivityProgress());
    }

    public PlayerActivitiesSnapshot snapshot() {
        Map<String, ActivityProgressSnapshot> snapshot = new HashMap<>();
        progress.forEach((activityId, value) -> snapshot.put(activityId, value.snapshot()));
        return new PlayerActivitiesSnapshot(snapshot);
    }

    public void restore(PlayerActivitiesSnapshot snapshot) {
        progress.clear();
        snapshot.progress().forEach((activityId, saved) -> {
            ActivityProgress restored = new ActivityProgress();
            restored.restore(saved);
            progress.put(activityId, restored);
        });
    }
}
