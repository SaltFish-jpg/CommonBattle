package com.commonbattle.game.session;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家出站 topic 策略注册表。
 * 统一维护不同业务推送的可靠性，避免业务处理器到处手写 ACK、丢弃和合并语义。
 */
public final class PlayerOutboundTopicPolicies {
    public static final String BAG_SNAPSHOT = "bag.snapshot";
    public static final String ACTIVITY_PROGRESS = "activity.progress";
    public static final String SCENE_SNAPSHOT = "scene.snapshot";
    public static final String COMBAT_FLOAT_TEXT = "combat.float";
    public static final String SHOP_SNAPSHOT = "shop.snapshot";
    public static final String GROWTH_SNAPSHOT = "growth.snapshot";
    public static final String BATTLE_SNAPSHOT = "battle.snapshot";
    public static final String TASK_PROGRESS = "task.progress";
    public static final String ACHIEVEMENT_PROGRESS = "achievement.progress";

    private final Map<String, PlayerOutboundTopicPolicy> policies = new ConcurrentHashMap<>();
    private final PlayerOutboundTopicPolicy defaultPolicy;

    public PlayerOutboundTopicPolicies() {
        this(PlayerOutboundTopicPolicy.reliable());
    }

    public PlayerOutboundTopicPolicies(PlayerOutboundTopicPolicy defaultPolicy) {
        this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
    }

    public static PlayerOutboundTopicPolicies gameDefaults() {
        return new PlayerOutboundTopicPolicies()
                .register(BAG_SNAPSHOT, PlayerOutboundTopicPolicy.coalescing("bag"))
                .register(ACTIVITY_PROGRESS, PlayerOutboundTopicPolicy.coalescing("activity"))
                .register(SCENE_SNAPSHOT, PlayerOutboundTopicPolicy.coalescing("scene"))
                .register(COMBAT_FLOAT_TEXT, PlayerOutboundTopicPolicy.bestEffort())
                .register(SHOP_SNAPSHOT, PlayerOutboundTopicPolicy.coalescing("shop"))
                .register(GROWTH_SNAPSHOT, PlayerOutboundTopicPolicy.coalescing("growth"))
                .register(BATTLE_SNAPSHOT, PlayerOutboundTopicPolicy.coalescing("battle"))
                .register(TASK_PROGRESS, PlayerOutboundTopicPolicy.coalescing("task"))
                .register(ACHIEVEMENT_PROGRESS, PlayerOutboundTopicPolicy.coalescing("achievement"));
    }

    public PlayerOutboundTopicPolicies register(String topic, PlayerOutboundTopicPolicy policy) {
        topic = normalizeTopic(topic);
        policies.put(topic, Objects.requireNonNull(policy, "policy"));
        return this;
    }

    public PlayerOutboundTopicPolicy resolve(String topic) {
        return policies.getOrDefault(normalizeTopic(topic), defaultPolicy);
    }

    public PlayerOutboundEnvelope envelope(Set<Long> recipients, String topic, Object payload) {
        PlayerOutboundTopicPolicy policy = resolve(topic);
        return new PlayerOutboundEnvelope(recipients, topic, payload, policy.mode(), policy.coalesceKey());
    }

    public Map<String, PlayerOutboundTopicPolicy> snapshot() {
        return Map.copyOf(policies);
    }

    private static String normalizeTopic(String topic) {
        topic = Objects.requireNonNull(topic, "topic").trim();
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        return topic;
    }
}
