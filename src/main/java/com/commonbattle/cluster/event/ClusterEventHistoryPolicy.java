package com.commonbattle.cluster.event;

import java.util.Map;
import java.util.Objects;

/**
 * 事件中心历史窗口容量策略。
 * 不同 topic 的事件频率差异很大，高频业务应单独配置更大的 replay 窗口。
 */
public record ClusterEventHistoryPolicy(int defaultLimit, Map<String, Integer> topicLimits) {
    public ClusterEventHistoryPolicy {
        if (defaultLimit <= 0) {
            throw new IllegalArgumentException("defaultLimit must be positive");
        }
        Objects.requireNonNull(topicLimits, "topicLimits");
        topicLimits.forEach((topic, limit) -> {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("topic must not be blank");
            }
            if (limit == null || limit <= 0) {
                throw new IllegalArgumentException("topic limit must be positive");
            }
        });
        topicLimits = Map.copyOf(topicLimits);
    }

    public static ClusterEventHistoryPolicy fixed(int limit) {
        return new ClusterEventHistoryPolicy(limit, Map.of());
    }

    public int limitOf(String topic) {
        return topicLimits.getOrDefault(topic, defaultLimit);
    }
}
