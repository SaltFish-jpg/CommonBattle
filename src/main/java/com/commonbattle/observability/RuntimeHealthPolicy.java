package com.commonbattle.observability;

/**
 * 运行时健康判定策略。
 * 阈值通常映射到告警规则：队列堆积说明 actor 处理不及时，outbox 堆积说明事件发布链路不健康。
 */
public record RuntimeHealthPolicy(int maxQueuedTasks, int maxPendingOutboxEvents) {
    public RuntimeHealthPolicy {
        if (maxQueuedTasks < 0) {
            throw new IllegalArgumentException("maxQueuedTasks must not be negative");
        }
        if (maxPendingOutboxEvents < 0) {
            throw new IllegalArgumentException("maxPendingOutboxEvents must not be negative");
        }
    }

    public static RuntimeHealthPolicy defaults() {
        return new RuntimeHealthPolicy(10_000, 0);
    }
}
