package com.commonbattle.game.battle;

import java.util.Map;

/**
 * 玩家战斗状态快照。
 */
public record PlayerBattleSnapshot(
        Map<String, BattleSettlementSnapshot> settlements,
        Map<String, BattleStageProgressSnapshot> stages
) {
    public PlayerBattleSnapshot(Map<String, BattleSettlementSnapshot> settlements) {
        this(settlements, Map.of());
    }

    public PlayerBattleSnapshot {
        settlements = Map.copyOf(settlements);
        stages = Map.copyOf(stages);
    }

    public static PlayerBattleSnapshot empty() {
        return new PlayerBattleSnapshot(Map.of(), Map.of());
    }
}
