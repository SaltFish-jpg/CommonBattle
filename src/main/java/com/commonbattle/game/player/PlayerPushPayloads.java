package com.commonbattle.game.player;

import com.commonbattle.game.achievement.AchievementProgressSnapshot;
import com.commonbattle.game.achievement.PlayerAchievementsSnapshot;
import com.commonbattle.game.activity.ActivityProgressSnapshot;
import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.BattleSettlementSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.battle.BattleStageProgressSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.task.PlayerTasksSnapshot;
import com.commonbattle.game.task.TaskProgressSnapshot;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家客户端推送 payload。
 * 这些类型面向网络传输，允许前期业务频繁变更时通过 protostuff 注册快速迭代。
 */
public final class PlayerPushPayloads {
    private PlayerPushPayloads() {
    }

    public static BagSnapshotPayload bag(BagSnapshot snapshot) {
        BagSnapshotPayload payload = new BagSnapshotPayload();
        payload.itemCounts = new LinkedHashMap<>(snapshot.itemCounts());
        return payload;
    }

    public static ActivityProgressPayload activities(PlayerActivitiesSnapshot snapshot) {
        ActivityProgressPayload payload = new ActivityProgressPayload();
        snapshot.progress().forEach((activityId, progress) ->
                payload.progress.put(activityId, progress(progress.value(), progress.claimed())));
        return payload;
    }

    public static GrowthSnapshotPayload growth(GrowthSnapshot snapshot) {
        GrowthSnapshotPayload payload = new GrowthSnapshotPayload();
        payload.level = snapshot.level();
        payload.exp = snapshot.exp();
        payload.stamina = snapshot.stamina();
        payload.maxStamina = snapshot.maxStamina();
        payload.staminaUpdatedAtMillis = snapshot.staminaUpdatedAt().toEpochMilli();
        return payload;
    }

    public static ShopSnapshotPayload shop(PlayerShopSnapshot snapshot) {
        ShopSnapshotPayload payload = new ShopSnapshotPayload();
        payload.lifetimePurchases = new LinkedHashMap<>(snapshot.lifetimePurchases());
        payload.dailyPurchases = new LinkedHashMap<>(snapshot.dailyPurchases());
        return payload;
    }

    public static BattleSnapshotPayload battle(PlayerBattleSnapshot snapshot) {
        BattleSnapshotPayload payload = new BattleSnapshotPayload();
        snapshot.settlements().forEach((settlementId, settlement) ->
                payload.settlements.put(settlementId, settlement(settlement)));
        snapshot.stages().forEach((stageId, stage) -> payload.stages.put(stageId, stage(stage)));
        return payload;
    }

    public static TaskProgressPayload tasks(PlayerTasksSnapshot snapshot) {
        TaskProgressPayload payload = new TaskProgressPayload();
        snapshot.progress().forEach((taskId, progress) ->
                payload.progress.put(taskId, progress(progress.value(), progress.claimed())));
        return payload;
    }

    public static AchievementProgressPayload achievements(PlayerAchievementsSnapshot snapshot) {
        AchievementProgressPayload payload = new AchievementProgressPayload();
        snapshot.progress().forEach((achievementId, progress) ->
                payload.progress.put(achievementId, progress(progress.value(), progress.claimed())));
        return payload;
    }

    private static ProgressPayload progress(int value, boolean claimed) {
        ProgressPayload payload = new ProgressPayload();
        payload.value = value;
        payload.claimed = claimed;
        return payload;
    }

    private static BattleSettlementPayload settlement(BattleSettlementSnapshot snapshot) {
        BattleSettlementPayload payload = new BattleSettlementPayload();
        payload.settlementId = snapshot.settlementId();
        payload.stageId = snapshot.stageId();
        payload.status = snapshot.status().name();
        payload.rounds = snapshot.rounds();
        payload.playerHp = snapshot.playerHp();
        payload.enemyHp = snapshot.enemyHp();
        payload.progressActivityId = snapshot.progressActivityId();
        payload.progressDelta = snapshot.progressDelta();
        payload.stars = snapshot.stars();
        payload.firstClear = snapshot.firstClear();
        payload.clearCount = snapshot.clearCount();
        payload.swept = snapshot.swept();
        return payload;
    }

    private static BattleStagePayload stage(BattleStageProgressSnapshot snapshot) {
        BattleStagePayload payload = new BattleStagePayload();
        payload.stageId = snapshot.stageId();
        payload.clearCount = snapshot.clearCount();
        payload.bestStars = snapshot.bestStars();
        payload.firstClearedAtMillis = snapshot.firstClearedAt().toEpochMilli();
        payload.updatedAtMillis = snapshot.updatedAt().toEpochMilli();
        return payload;
    }

    public static class BagSnapshotPayload {
        public Map<String, Integer> itemCounts = new LinkedHashMap<>();
    }

    public static class ActivityProgressPayload {
        public Map<String, ProgressPayload> progress = new LinkedHashMap<>();
    }

    public static class GrowthSnapshotPayload {
        public int level;
        public int exp;
        public int stamina;
        public int maxStamina;
        public long staminaUpdatedAtMillis;
    }

    public static class ShopSnapshotPayload {
        public Map<String, Integer> lifetimePurchases = new LinkedHashMap<>();
        public Map<String, Integer> dailyPurchases = new LinkedHashMap<>();
    }

    public static class BattleSnapshotPayload {
        public Map<String, BattleSettlementPayload> settlements = new LinkedHashMap<>();
        public Map<String, BattleStagePayload> stages = new LinkedHashMap<>();
    }

    public static class TaskProgressPayload {
        public Map<String, ProgressPayload> progress = new LinkedHashMap<>();
    }

    public static class AchievementProgressPayload {
        public Map<String, ProgressPayload> progress = new LinkedHashMap<>();
    }

    public static class ProgressPayload {
        public int value;
        public boolean claimed;
    }

    public static class BattleSettlementPayload {
        public String settlementId = "";
        public String stageId = "";
        public String status = "";
        public int rounds;
        public int playerHp;
        public int enemyHp;
        public String progressActivityId = "";
        public int progressDelta;
        public int stars;
        public boolean firstClear;
        public int clearCount;
        public boolean swept;
    }

    public static class BattleStagePayload {
        public String stageId = "";
        public int clearCount;
        public int bestStars;
        public long firstClearedAtMillis = Instant.EPOCH.toEpochMilli();
        public long updatedAtMillis = Instant.EPOCH.toEpochMilli();
    }
}
