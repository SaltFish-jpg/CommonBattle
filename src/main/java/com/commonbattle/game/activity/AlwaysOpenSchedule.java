package com.commonbattle.game.activity;

/**
 * 永久开放的活动时间策略。
 */
public final class AlwaysOpenSchedule implements ActivitySchedule {
    public static final AlwaysOpenSchedule INSTANCE = new AlwaysOpenSchedule();

    private AlwaysOpenSchedule() {
    }

    @Override
    public boolean isOpen(ActivityAccessContext context) {
        return true;
    }
}
