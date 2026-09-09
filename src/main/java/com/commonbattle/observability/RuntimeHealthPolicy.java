package com.commonbattle.observability;

/**
 * 运行时健康判定策略。
 * 阈值通常映射到告警规则：队列堆积说明 actor 处理不及时，outbox 堆积说明事件发布链路不健康。
 */
public record RuntimeHealthPolicy(
        int maxQueuedTasks,
        int maxPendingOutboxEvents,
        long maxMigrationPendingTaskAgeMillis,
        int maxSceneActiveScenes,
        int maxSceneActivePlayers,
        int maxSceneShardHotspotPlayers,
        int maxPlayerBusinessPendingResponses,
        long maxPlayerBusinessPendingResponseAgeMillis
) {
    public RuntimeHealthPolicy(
            int maxQueuedTasks,
            int maxPendingOutboxEvents,
            long maxMigrationPendingTaskAgeMillis
    ) {
        this(maxQueuedTasks, maxPendingOutboxEvents, maxMigrationPendingTaskAgeMillis, 0, 0, 0, 0, 0);
    }

    public RuntimeHealthPolicy(
            int maxQueuedTasks,
            int maxPendingOutboxEvents,
            long maxMigrationPendingTaskAgeMillis,
            int maxSceneActiveScenes,
            int maxSceneActivePlayers,
            int maxSceneShardHotspotPlayers
    ) {
        this(maxQueuedTasks, maxPendingOutboxEvents, maxMigrationPendingTaskAgeMillis,
                maxSceneActiveScenes, maxSceneActivePlayers, maxSceneShardHotspotPlayers, 0, 0);
    }

    public RuntimeHealthPolicy {
        if (maxQueuedTasks < 0) {
            throw new IllegalArgumentException("maxQueuedTasks must not be negative");
        }
        if (maxPendingOutboxEvents < 0) {
            throw new IllegalArgumentException("maxPendingOutboxEvents must not be negative");
        }
        if (maxMigrationPendingTaskAgeMillis < 0) {
            throw new IllegalArgumentException("maxMigrationPendingTaskAgeMillis must not be negative");
        }
        if (maxSceneActiveScenes < 0) {
            throw new IllegalArgumentException("maxSceneActiveScenes must not be negative");
        }
        if (maxSceneActivePlayers < 0) {
            throw new IllegalArgumentException("maxSceneActivePlayers must not be negative");
        }
        if (maxSceneShardHotspotPlayers < 0) {
            throw new IllegalArgumentException("maxSceneShardHotspotPlayers must not be negative");
        }
        if (maxPlayerBusinessPendingResponses < 0) {
            throw new IllegalArgumentException("maxPlayerBusinessPendingResponses must not be negative");
        }
        if (maxPlayerBusinessPendingResponseAgeMillis < 0) {
            throw new IllegalArgumentException("maxPlayerBusinessPendingResponseAgeMillis must not be negative");
        }
    }

    public static RuntimeHealthPolicy defaults() {
        return new RuntimeHealthPolicy(10_000, 0, 300_000, 0, 0, 0, 0, 0);
    }
}
