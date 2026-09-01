package com.commonbattle.game.activity;

import java.time.Instant;
import java.util.Objects;

/**
 * 按自然绝对时间开放的活动。
 */
public final class NaturalTimeSchedule implements ActivitySchedule {
    private final Instant startInclusive;
    private final Instant endExclusive;

    public NaturalTimeSchedule(Instant startInclusive, Instant endExclusive) {
        this.startInclusive = Objects.requireNonNull(startInclusive, "startInclusive");
        this.endExclusive = Objects.requireNonNull(endExclusive, "endExclusive");
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Activity start time must be before end time");
        }
    }

    @Override
    public boolean isOpen(ActivityAccessContext context) {
        Instant now = context.now();
        return !now.isBefore(startInclusive) && now.isBefore(endExclusive);
    }
}
