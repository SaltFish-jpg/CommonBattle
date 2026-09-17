package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityProgressSnapshot;
import com.commonbattle.game.activity.PlayerActivitiesSnapshot;
import com.commonbattle.game.achievement.AchievementProgressSnapshot;
import com.commonbattle.game.achievement.PlayerAchievementsSnapshot;
import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.bag.BagSnapshot;
import com.commonbattle.game.battle.BattleSettlementSnapshot;
import com.commonbattle.game.battle.BattleSettlementStatus;
import com.commonbattle.game.battle.BattleStageProgressSnapshot;
import com.commonbattle.game.battle.PlayerBattleSnapshot;
import com.commonbattle.game.growth.GrowthSnapshot;
import com.commonbattle.game.shop.PlayerShopSnapshot;
import com.commonbattle.game.task.PlayerTasksSnapshot;
import com.commonbattle.game.task.TaskProgressSnapshot;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 protostuff DTO 的玩家快照序列化器。
 * DTO 字段只做尾部追加，业务 record 可继续按领域模型演进。
 */
public final class ProtostuffPlayerStateSnapshotSerializer implements PlayerStateSnapshotSerializer {
    private static final Schema<StateDto> SCHEMA = RuntimeSchema.getSchema(StateDto.class);

    @Override
    public byte[] serialize(PlayerStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        LinkedBuffer buffer = LinkedBuffer.allocate(1024);
        try {
            return ProtostuffIOUtil.toByteArray(toDto(snapshot), SCHEMA, buffer);
        } finally {
            buffer.clear();
        }
    }

    @Override
    public PlayerStateSnapshot deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        StateDto dto = new StateDto();
        ProtostuffIOUtil.mergeFrom(bytes, dto, SCHEMA);
        return fromDto(dto);
    }

    private StateDto toDto(PlayerStateSnapshot snapshot) {
        StateDto dto = new StateDto();
        dto.playerId = snapshot.playerId();
        dto.createdAtMillis = snapshot.createdAt().toEpochMilli();
        dto.savedAtMillis = snapshot.savedAt().toEpochMilli();
        dto.eventRevision = snapshot.eventRevision();
        dto.revision = snapshot.revision();
        dto.bag = intEntries(snapshot.bag().itemCounts());
        dto.activities = progressEntries(snapshot.activities().progress());
        dto.growthLevel = snapshot.growth().level();
        dto.growthExp = snapshot.growth().exp();
        dto.growthStamina = snapshot.growth().stamina();
        dto.growthMaxStamina = snapshot.growth().maxStamina();
        dto.growthStaminaUpdatedAtMillis = snapshot.growth().staminaUpdatedAt().toEpochMilli();
        dto.shopLifetime = intEntries(snapshot.shop().lifetimePurchases());
        dto.shopDaily = intEntries(snapshot.shop().dailyPurchases());
        dto.battleSettlements = settlementEntries(snapshot.battle().settlements());
        dto.battleStages = stageEntries(snapshot.battle().stages());
        dto.tasks = taskEntries(snapshot.tasks().progress());
        dto.achievements = achievementEntries(snapshot.achievements().progress());
        return dto;
    }

    private PlayerStateSnapshot fromDto(StateDto dto) {
        return new PlayerStateSnapshot(
                dto.playerId,
                instant(dto.createdAtMillis),
                new BagSnapshot(intMap(dto.bag)),
                new PlayerActivitiesSnapshot(activityMap(dto.activities)),
                growth(dto),
                new PlayerShopSnapshot(intMap(dto.shopLifetime), intMap(dto.shopDaily)),
                new PlayerBattleSnapshot(settlementMap(dto.battleSettlements), stageMap(dto.battleStages)),
                new PlayerTasksSnapshot(taskMap(dto.tasks)),
                new PlayerAchievementsSnapshot(achievementMap(dto.achievements)),
                dto.eventRevision,
                dto.revision,
                instant(dto.savedAtMillis)
        );
    }

    private GrowthSnapshot growth(StateDto dto) {
        int maxStamina = dto.growthMaxStamina <= 0 ? GrowthSnapshot.DEFAULT_MAX_STAMINA : dto.growthMaxStamina;
        int stamina = dto.growthMaxStamina <= 0 ? maxStamina : Math.max(0, Math.min(dto.growthStamina, maxStamina));
        return new GrowthSnapshot(
                dto.growthLevel <= 0 ? 1 : dto.growthLevel,
                Math.max(0, dto.growthExp),
                stamina,
                maxStamina,
                instant(dto.growthStaminaUpdatedAtMillis)
        );
    }

    private List<IntEntryDto> intEntries(Map<String, Integer> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    IntEntryDto dto = new IntEntryDto();
                    dto.key = entry.getKey();
                    dto.value = entry.getValue();
                    return dto;
                })
                .toList();
    }

    private Map<String, Integer> intMap(List<IntEntryDto> entries) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (IntEntryDto entry : list(entries)) {
            values.put(entry.key, entry.value);
        }
        return values;
    }

    private List<ProgressDto> progressEntries(Map<String, ActivityProgressSnapshot> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> progress(entry.getKey(), entry.getValue().value(), entry.getValue().claimed()))
                .toList();
    }

    private List<ProgressDto> taskEntries(Map<String, TaskProgressSnapshot> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> progress(entry.getKey(), entry.getValue().value(), entry.getValue().claimed()))
                .toList();
    }

    private List<ProgressDto> achievementEntries(Map<String, AchievementProgressSnapshot> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> progress(entry.getKey(), entry.getValue().value(), entry.getValue().claimed()))
                .toList();
    }

    private ProgressDto progress(String key, int value, boolean claimed) {
        ProgressDto dto = new ProgressDto();
        dto.key = key;
        dto.value = value;
        dto.claimed = claimed;
        return dto;
    }

    private Map<String, ActivityProgressSnapshot> activityMap(List<ProgressDto> entries) {
        Map<String, ActivityProgressSnapshot> values = new LinkedHashMap<>();
        for (ProgressDto entry : list(entries)) {
            values.put(entry.key, new ActivityProgressSnapshot(entry.value, entry.claimed));
        }
        return values;
    }

    private Map<String, TaskProgressSnapshot> taskMap(List<ProgressDto> entries) {
        Map<String, TaskProgressSnapshot> values = new LinkedHashMap<>();
        for (ProgressDto entry : list(entries)) {
            values.put(entry.key, new TaskProgressSnapshot(entry.value, entry.claimed));
        }
        return values;
    }

    private Map<String, AchievementProgressSnapshot> achievementMap(List<ProgressDto> entries) {
        Map<String, AchievementProgressSnapshot> values = new LinkedHashMap<>();
        for (ProgressDto entry : list(entries)) {
            values.put(entry.key, new AchievementProgressSnapshot(entry.value, entry.claimed));
        }
        return values;
    }

    private List<BattleSettlementDto> settlementEntries(Map<String, BattleSettlementSnapshot> values) {
        return values.values().stream()
                .sorted(Comparator.comparing(BattleSettlementSnapshot::settlementId))
                .map(settlement -> {
                    BattleSettlementDto dto = new BattleSettlementDto();
                    dto.settlementId = settlement.settlementId();
                    dto.stageId = settlement.stageId();
                    dto.status = settlement.status().name();
                    dto.rounds = settlement.rounds();
                    dto.playerHp = settlement.playerHp();
                    dto.enemyHp = settlement.enemyHp();
                    dto.rewardChanges = encodeBagChanges(settlement.rewardResult().changes());
                    dto.progressActivityId = settlement.progressActivityId();
                    dto.progressDelta = settlement.progressDelta();
                    dto.stars = settlement.stars();
                    dto.firstClear = settlement.firstClear();
                    dto.clearCount = settlement.clearCount();
                    dto.swept = settlement.swept();
                    return dto;
                })
                .toList();
    }

    private Map<String, BattleSettlementSnapshot> settlementMap(List<BattleSettlementDto> entries) {
        Map<String, BattleSettlementSnapshot> values = new LinkedHashMap<>();
        for (BattleSettlementDto entry : list(entries)) {
            BattleSettlementSnapshot snapshot = new BattleSettlementSnapshot(
                    entry.settlementId,
                    entry.stageId,
                    BattleSettlementStatus.valueOf(entry.status),
                    entry.rounds,
                    entry.playerHp,
                    entry.enemyHp,
                    new BagResult(decodeBagChanges(entry.rewardChanges)),
                    entry.progressActivityId,
                    entry.progressDelta,
                    entry.stars,
                    entry.firstClear,
                    entry.clearCount,
                    entry.swept
            );
            values.put(snapshot.settlementId(), snapshot);
        }
        return values;
    }

    private List<BagChangeDto> encodeBagChanges(List<BagChange> changes) {
        return changes.stream()
                .map(change -> {
                    BagChangeDto dto = new BagChangeDto();
                    dto.itemId = change.itemId();
                    dto.before = change.before();
                    dto.after = change.after();
                    return dto;
                })
                .toList();
    }

    private List<BagChange> decodeBagChanges(List<BagChangeDto> changes) {
        return list(changes).stream()
                .map(change -> new BagChange(change.itemId, change.before, change.after))
                .toList();
    }

    private List<BattleStageDto> stageEntries(Map<String, BattleStageProgressSnapshot> values) {
        return values.values().stream()
                .sorted(Comparator.comparing(BattleStageProgressSnapshot::stageId))
                .map(stage -> {
                    BattleStageDto dto = new BattleStageDto();
                    dto.stageId = stage.stageId();
                    dto.clearCount = stage.clearCount();
                    dto.bestStars = stage.bestStars();
                    dto.firstClearedAtMillis = stage.firstClearedAt().toEpochMilli();
                    dto.updatedAtMillis = stage.updatedAt().toEpochMilli();
                    return dto;
                })
                .toList();
    }

    private Map<String, BattleStageProgressSnapshot> stageMap(List<BattleStageDto> entries) {
        Map<String, BattleStageProgressSnapshot> values = new LinkedHashMap<>();
        for (BattleStageDto entry : list(entries)) {
            BattleStageProgressSnapshot snapshot = new BattleStageProgressSnapshot(
                    entry.stageId,
                    entry.clearCount,
                    entry.bestStars,
                    instant(entry.firstClearedAtMillis),
                    instant(entry.updatedAtMillis)
            );
            values.put(snapshot.stageId(), snapshot);
        }
        return values;
    }

    private Instant instant(long epochMillis) {
        return epochMillis <= 0 ? Instant.EPOCH : Instant.ofEpochMilli(epochMillis);
    }

    private <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static final class StateDto {
        public long playerId;
        public long createdAtMillis;
        public long savedAtMillis;
        public long eventRevision;
        public long revision;
        public List<IntEntryDto> bag = new ArrayList<>();
        public List<ProgressDto> activities = new ArrayList<>();
        public int growthLevel;
        public int growthExp;
        public int growthStamina;
        public int growthMaxStamina;
        public long growthStaminaUpdatedAtMillis;
        public List<IntEntryDto> shopLifetime = new ArrayList<>();
        public List<IntEntryDto> shopDaily = new ArrayList<>();
        public List<BattleSettlementDto> battleSettlements = new ArrayList<>();
        public List<BattleStageDto> battleStages = new ArrayList<>();
        public List<ProgressDto> tasks = new ArrayList<>();
        public List<ProgressDto> achievements = new ArrayList<>();
    }

    public static final class IntEntryDto {
        public String key = "";
        public int value;
    }

    public static final class ProgressDto {
        public String key = "";
        public int value;
        public boolean claimed;
    }

    public static final class BagChangeDto {
        public String itemId = "";
        public int before;
        public int after;
    }

    public static final class BattleSettlementDto {
        public String settlementId = "";
        public String stageId = "";
        public String status = BattleSettlementStatus.VICTORY.name();
        public int rounds;
        public int playerHp;
        public int enemyHp;
        public List<BagChangeDto> rewardChanges = new ArrayList<>();
        public String progressActivityId = "";
        public int progressDelta;
        public int stars;
        public boolean firstClear;
        public int clearCount;
        public boolean swept;
    }

    public static final class BattleStageDto {
        public String stageId = "";
        public int clearCount;
        public int bestStars;
        public long firstClearedAtMillis;
        public long updatedAtMillis;
    }
}
