package com.commonbattle.game.activity;

/**
 * 玩家能否参与活动的条件。
 * 条件只读取玩家最小视图，不直接修改任何玩家状态。
 */
public interface ParticipationCondition {
    boolean allows(ActivityAccessContext context);

    static ParticipationCondition always() {
        return context -> true;
    }

    static ParticipationCondition minLevel(int level) {
        return new MinLevelCondition(level);
    }

    static ParticipationCondition and(ParticipationCondition first, ParticipationCondition second) {
        return context -> first.allows(context) && second.allows(context);
    }
}
