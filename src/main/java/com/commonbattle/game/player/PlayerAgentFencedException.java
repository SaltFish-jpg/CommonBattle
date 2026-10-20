package com.commonbattle.game.player;

/**
 * 已隔离玩家 Agent 拒绝继续执行状态变更。
 */
public final class PlayerAgentFencedException extends IllegalStateException {
    public PlayerAgentFencedException(long playerId) {
        super("player agent is fenced: " + playerId);
    }
}
