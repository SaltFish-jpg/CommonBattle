package com.commonbattle.game.growth;

/**
 * 玩家养成状态。
 */
public final class GrowthProfile {
    private int level = 1;
    private int exp;

    public int level() {
        return level;
    }

    public int exp() {
        return exp;
    }

    public GrowthSnapshot snapshot() {
        return new GrowthSnapshot(level, exp);
    }

    public void restore(GrowthSnapshot snapshot) {
        level = snapshot.level();
        exp = snapshot.exp();
    }

    void addExp(int value, int expPerLevel) {
        exp += value;
        while (exp >= expPerLevel) {
            exp -= expPerLevel;
            level++;
        }
    }
}
