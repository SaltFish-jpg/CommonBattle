package com.commonbattle.game.activity;

import java.time.Instant;
import java.util.Objects;

/**
 * 活动开放和参与判断所需的运行时信息。
 * 自然时间看 now，开服时间看 serverOpenTime，参与条件看 participant。
 */
public record ActivityAccessContext(Instant now, Instant serverOpenTime, ActivityParticipant participant) {
    public ActivityAccessContext {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(serverOpenTime, "serverOpenTime");
        Objects.requireNonNull(participant, "participant");
    }

    public static ActivityAccessContext alwaysAllowed() {
        return new ActivityAccessContext(Instant.EPOCH, Instant.EPOCH, ActivityParticipant.none());
    }
}
