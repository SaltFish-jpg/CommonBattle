package com.commonbattle.game.activity;

import java.time.Duration;
import java.time.Instant;

/**
 * 活动开放时间策略。
 * 自然时间适合节日活动，开服时间适合新区第 N 天活动。
 */
public interface ActivitySchedule {
    boolean isOpen(ActivityAccessContext context);

    static ActivitySchedule alwaysOpen() {
        return context -> true;
    }

    static ActivitySchedule naturalWindow(Instant startInclusive, Instant endExclusive) {
        return new NaturalTimeSchedule(startInclusive, endExclusive);
    }

    static ActivitySchedule openServerWindow(Duration startAfterOpen, Duration endAfterOpen) {
        return new OpenServerTimeSchedule(startAfterOpen, endAfterOpen);
    }
}
