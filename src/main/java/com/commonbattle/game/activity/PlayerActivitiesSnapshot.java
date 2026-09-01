package com.commonbattle.game.activity;

import java.util.Map;

/**
 * 玩家活动状态可持久化快照。
 */
public record PlayerActivitiesSnapshot(Map<String, ActivityProgressSnapshot> progress) {
    public PlayerActivitiesSnapshot {
        progress = Map.copyOf(progress);
    }
}
