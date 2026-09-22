package com.commonbattle.game.battle;

import com.commonbattle.game.GameBusinessErrorCodes;
import com.commonbattle.game.GameBusinessFailure;

/**
 * 战斗入口体力不足。
 */
public final class BattleStaminaNotEnoughException extends IllegalStateException implements GameBusinessFailure {
    private final String stageId;
    private final int required;
    private final int actual;

    public BattleStaminaNotEnoughException(String stageId, int required, int actual) {
        super("Not enough stamina for battle stage " + stageId + ": required=" + required + ", actual=" + actual);
        this.stageId = stageId;
        this.required = required;
        this.actual = actual;
    }

    public String stageId() {
        return stageId;
    }

    public int required() {
        return required;
    }

    public int actual() {
        return actual;
    }

    @Override
    public String code() {
        return GameBusinessErrorCodes.BATTLE_STAMINA_NOT_ENOUGH;
    }
}
