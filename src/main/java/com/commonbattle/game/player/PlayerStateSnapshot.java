package com.commonbattle.game.player;

import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.achievement.PlayerAchievementsSnapshot;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.task.PlayerTasksSnapshot;

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
        PlayerShopSnapshot shop,
        PlayerBattleSnapshot battle,
        PlayerTasksSnapshot tasks,
        PlayerAchievementsSnapshot achievements,
        long eventRevision,
        long revision,
        Instant savedAt
) {
    public PlayerStateSnapshot(
            long playerId,
            Instant createdAt,
            BagSnapshot bag,
            PlayerActivitiesSnapshot activities,
            GrowthSnapshot growth,
            long revision,
            Instant savedAt
    ) {
        this(playerId, createdAt, bag, activities, growth, PlayerShopSnapshot.empty(),
                PlayerBattleSnapshot.empty(), PlayerTasksSnapshot.empty(),
                PlayerAchievementsSnapshot.empty(), 0, revision, savedAt);
    }

    public PlayerStateSnapshot(
            long playerId,
            Instant createdAt,
            BagSnapshot bag,
            PlayerActivitiesSnapshot activities,
            GrowthSnapshot growth,
            PlayerShopSnapshot shop,
            long revision,
            Instant savedAt
    ) {
        this(playerId, createdAt, bag, activities, growth, shop, PlayerBattleSnapshot.empty(),
                PlayerTasksSnapshot.empty(), PlayerAchievementsSnapshot.empty(), 0, revision, savedAt);
    }

    public PlayerStateSnapshot(
            long playerId,
            Instant createdAt,
            BagSnapshot bag,
            PlayerActivitiesSnapshot activities,
            GrowthSnapshot growth,
            PlayerShopSnapshot shop,
            PlayerBattleSnapshot battle,
            long revision,
            Instant savedAt
    ) {
        this(playerId, createdAt, bag, activities, growth, shop, battle, PlayerTasksSnapshot.empty(),
                PlayerAchievementsSnapshot.empty(), 0, revision, savedAt);
    }

    public PlayerStateSnapshot(
            long playerId,
            Instant createdAt,
            BagSnapshot bag,
            PlayerActivitiesSnapshot activities,
            GrowthSnapshot growth,
            PlayerShopSnapshot shop,
            PlayerBattleSnapshot battle,
            PlayerTasksSnapshot tasks,
            long revision,
            Instant savedAt
    ) {
        this(playerId, createdAt, bag, activities, growth, shop, battle, tasks,
                PlayerAchievementsSnapshot.empty(), 0, revision, savedAt);
    }

    public PlayerStateSnapshot(
            long playerId,
            Instant createdAt,
            BagSnapshot bag,
            PlayerActivitiesSnapshot activities,
            GrowthSnapshot growth,
            PlayerShopSnapshot shop,
            PlayerBattleSnapshot battle,
            PlayerTasksSnapshot tasks,
            PlayerAchievementsSnapshot achievements,
            long revision,
            Instant savedAt
    ) {
        this(playerId, createdAt, bag, activities, growth, shop, battle, tasks, achievements, 0, revision, savedAt);
    }

    public PlayerStateSnapshot {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(growth, "growth");
        Objects.requireNonNull(shop, "shop");
        Objects.requireNonNull(battle, "battle");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(achievements, "achievements");
        Objects.requireNonNull(savedAt, "savedAt");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (eventRevision < 0) {
            throw new IllegalArgumentException("eventRevision must not be negative");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
