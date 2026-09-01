package com.commonbattle.game.player;

import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;

import java.time.Instant;
import java.util.Objects;

/**
 * 玩家通用业务状态快照。
 * 只保存业务状态数据；服务实现、活动配置和物品配置由进程启动时重新注入。
 */
public record PlayerStateSnapshot(
        long playerId,
        Instant createdAt,
        BagSnapshot bag,
        PlayerActivitiesSnapshot activities,
        GrowthSnapshot growth,
        long revision,
        Instant savedAt
) {
    public PlayerStateSnapshot {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(growth, "growth");
        Objects.requireNonNull(savedAt, "savedAt");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
