package com.commonbattle.game.battle;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 玩家战斗结算状态。
 * 该对象只应在玩家 Agent 邮箱内读写，用 settlementId 防止重试请求重复发奖。
 */
public final class PlayerBattleState {
    private final Map<String, BattleSettlementSnapshot> settlements = new HashMap<>();
    private final Map<String, BattleStageProgress> stages = new HashMap<>();

    public Optional<BattleSettlementResult> replay(String settlementId, String stageId) {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(stageId, "stageId");
        if (settlementId.isBlank()) {
            return Optional.empty();
        }
        BattleSettlementSnapshot snapshot = settlements.get(settlementId);
        if (snapshot == null) {
            return Optional.empty();
        }
        if (!snapshot.stageId().equals(stageId)) {
            throw new IllegalStateException("battle settlement id conflicts with stage");
        }
        return Optional.of(snapshot.toResult().asReplayed());
    }

    public void record(BattleSettlementResult result) {
        Objects.requireNonNull(result, "result");
        if (result.settlementId().isBlank()) {
            return;
        }
        BattleSettlementSnapshot snapshot = new BattleSettlementSnapshot(
                result.settlementId(),
                result.stageId(),
                result.status(),
                result.rounds(),
                result.playerHp(),
                result.enemyHp(),
                result.rewardResult(),
                result.progressActivityId(),
                result.progressDelta(),
                result.stars(),
                result.firstClear(),
                result.clearCount(),
                result.swept()
        );
        settlements.putIfAbsent(result.settlementId(), snapshot);
    }

    public BattleStageProgress progress(String stageId) {
        return stages.computeIfAbsent(stageId, BattleStageProgress::new);
    }

    public Optional<BattleStageProgress> findProgress(String stageId) {
        Objects.requireNonNull(stageId, "stageId");
        return Optional.ofNullable(stages.get(stageId));
    }

    public PlayerBattleSnapshot snapshot() {
        Map<String, BattleStageProgressSnapshot> stageSnapshots = new HashMap<>();
        stages.forEach((stageId, progress) -> stageSnapshots.put(stageId, progress.snapshot()));
        return new PlayerBattleSnapshot(settlements, stageSnapshots);
    }

    public void restore(PlayerBattleSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        settlements.clear();
        settlements.putAll(snapshot.settlements());
        stages.clear();
        snapshot.stages().forEach((stageId, saved) -> {
            BattleStageProgress progress = new BattleStageProgress(stageId);
            progress.restore(saved);
            stages.put(stageId, progress);
        });
    }
}
