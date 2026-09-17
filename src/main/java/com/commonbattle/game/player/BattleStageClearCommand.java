package com.commonbattle.game.player;

import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.player.event.BattleStageClearedEvent;

import java.util.Objects;

/**
 * 玩家通关 PVE 关卡的业务命令。
 */
public record BattleStageClearCommand(String settlementId, String stageId) implements PlayerBusinessCommand<BattleSettlementResult> {
    public BattleStageClearCommand(String stageId) {
        this("", stageId);
    }

    public BattleStageClearCommand {
        settlementId = Objects.requireNonNullElse(settlementId, "");
        Objects.requireNonNull(stageId, "stageId");
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.BATTLE_CLEAR_STAGE;
    }

    @Override
    public BattleSettlementResult execute(PlayerGameExecution execution) {
        execution.consumeBattleStaminaForNewSettlement(
                execution.runtime().requireBattleService().requireStage(stageId),
                settlementId
        );
        BattleSettlementResult result = execution.runtime().requireBattleService().clear(
                execution.profile().bag(),
                execution.profile().battle(),
                execution.activityAccess().now(),
                settlementId,
                stageId
        );
        if (result.victory()) {
            execution.publish(BattleStageClearedEvent.from(execution.profile().playerId(), result));
            if (!result.replayed()) {
                execution.pushBattleSettlementSnapshots(result);
            }
        }
        return result;
    }
}
