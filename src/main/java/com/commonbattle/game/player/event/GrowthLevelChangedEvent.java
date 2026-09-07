package com.commonbattle.game.player.event;

/**
 * 玩家养成等级变化后产生的业务事件。
 */
public record GrowthLevelChangedEvent(long playerId, int beforeLevel, int afterLevel) implements PlayerDomainEvent {
    public static final String TYPE = "growth.level.changed";

    public GrowthLevelChangedEvent {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (beforeLevel <= 0 || afterLevel <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String subject() {
        return "";
    }

    @Override
    public int delta() {
        return afterLevel - beforeLevel;
    }
}
