package com.commonbattle.game.activity;

import java.time.Instant;

/**
 * 活动系统读取玩家参与资格的最小视图。
 */
public interface ActivityParticipant {
    long playerId();

    int level();

    Instant createdAt();

    static ActivityParticipant none() {
        return new ActivityParticipant() {
            @Override
            public long playerId() {
                return 0;
            }

            @Override
            public int level() {
                return 1;
            }

            @Override
            public Instant createdAt() {
                return Instant.EPOCH;
            }
        };
    }
}
