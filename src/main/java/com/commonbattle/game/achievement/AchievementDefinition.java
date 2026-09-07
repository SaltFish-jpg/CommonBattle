package com.commonbattle.game.achievement;

import com.commonbattle.game.bag.Reward;
import com.commonbattle.game.player.event.EventProgressRule;

import java.util.Objects;

/**
 * 玩家成就配置。
 * 成就是长期目标，通常不会随活动周期重置，进度由玩家业务事件推进。
 */
public record AchievementDefinition(
        String achievementId,
        EventProgressRule progressRule,
        int threshold,
        Reward reward
) {
    public AchievementDefinition(String achievementId, String eventType, String subject, int threshold, Reward reward) {
        this(achievementId, EventProgressRule.of(eventType, subject), threshold, reward);
    }

    public AchievementDefinition {
        Objects.requireNonNull(achievementId, "achievementId");
        Objects.requireNonNull(progressRule, "progressRule");
        Objects.requireNonNull(reward, "reward");
        if (achievementId.isBlank()) {
            throw new IllegalArgumentException("achievementId must not be blank");
        }
        if (!progressRule.enabled()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
    }
}
