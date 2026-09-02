package com.commonbattle.game.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 按开服后相对时间开放的活动。
 */
public final class OpenServerTimeSchedule implements ActivitySchedule {
    private final Duration startAfterOpen;
    private final Duration endAfterOpen;

    public OpenServerTimeSchedule(Duration startAfterOpen, Duration endAfterOpen) {
        this.startAfterOpen = Objects.requireNonNull(startAfterOpen, "startAfterOpen");
        this.endAfterOpen = Objects.requireNonNull(endAfterOpen, "endAfterOpen");
        if (startAfterOpen.isNegative() || endAfterOpen.isNegative() || startAfterOpen.compareTo(endAfterOpen) >= 0) {
            throw new IllegalArgumentException("Open server window is invalid");
        }
    }

    @Override
    public boolean isOpen(ActivityAccessContext context) {
        Instant start = context.serverOpenTime().plus(startAfterOpen);
        Instant end = context.serverOpenTime().plus(endAfterOpen);
        Instant now = context.now();
        return !now.isBefore(start) && now.isBefore(end);
    }

    public Duration startAfterOpen() {
        return startAfterOpen;
    }

    public Duration endAfterOpen() {
        return endAfterOpen;
    }
}
