package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家出站 topic 的投递策略。
 * 业务模块只声明 topic，可靠性、是否合并和合并槽位由统一策略表维护。
 */
public record PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode mode, String coalesceKey) {
    public PlayerOutboundTopicPolicy {
        mode = Objects.requireNonNull(mode, "mode");
        coalesceKey = Objects.requireNonNullElse(coalesceKey, "").trim();
        if (mode == PlayerOutboundDeliveryMode.COALESCING && coalesceKey.isBlank()) {
            throw new IllegalArgumentException("coalesceKey must not be blank for coalescing policy");
        }
    }

    public static PlayerOutboundTopicPolicy reliable() {
        return new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.RELIABLE, "");
    }

    public static PlayerOutboundTopicPolicy bestEffort() {
        return new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.BEST_EFFORT, "");
    }

    public static PlayerOutboundTopicPolicy coalescing(String coalesceKey) {
        return new PlayerOutboundTopicPolicy(PlayerOutboundDeliveryMode.COALESCING, coalesceKey);
    }
}
