package com.commonbattle.game.activity;

/**
 * 单个玩家在单个活动上的状态。
 */
public final class ActivityProgress {
    private int value;
    private boolean claimed;

    public int value() {
        return value;
    }

    public boolean claimed() {
        return claimed;
    }

    ActivityProgressSnapshot snapshot() {
        return new ActivityProgressSnapshot(value, claimed);
    }

    void restore(ActivityProgressSnapshot snapshot) {
        value = snapshot.value();
        claimed = snapshot.claimed();
    }

    void increase(int delta) {
        value += delta;
    }

    void claim() {
        claimed = true;
    }
}
