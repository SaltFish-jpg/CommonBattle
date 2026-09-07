package com.commonbattle.game.player.event;

import com.commonbattle.game.battle.BattleSettlementResult;

import java.util.Objects;

/**
 * 玩家通关或扫荡 PVE 关卡后产生的业务事件。
 */
public record BattleStageClearedEvent(
        long playerId,
        String stageId,
        int stars,
        boolean firstClear,
        int clearCount,
        boolean swept,
        boolean replayed,
        String activityId,
        int progressDelta
) implements PlayerDomainEvent {
    public static final String TYPE = "battle.stage.cleared";

    public BattleStageClearedEvent {
        Objects.requireNonNull(stageId, "stageId");
        activityId = Objects.requireNonNullElse(activityId, "");
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (stageId.isBlank()) {
            throw new IllegalArgumentException("stageId must not be blank");
        }
    }

    public static BattleStageClearedEvent from(long playerId, BattleSettlementResult result) {
        Objects.requireNonNull(result, "result");
        return new BattleStageClearedEvent(
                playerId,
                result.stageId(),
                result.stars(),
                result.firstClear(),
                result.clearCount(),
                result.swept(),
                result.replayed(),
                result.progressActivityId(),
                result.progressDelta()
        );
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String subject() {
        return stageId;
    }

    @Override
    public int delta() {
        return progressDelta > 0 ? progressDelta : 1;
    }
}
