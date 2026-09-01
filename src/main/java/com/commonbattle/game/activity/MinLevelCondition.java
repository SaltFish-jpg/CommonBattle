package com.commonbattle.game.activity;

/**
 * 最低等级参与条件。
 */
public final class MinLevelCondition implements ParticipationCondition {
    private final int level;

    public MinLevelCondition(int level) {
        if (level <= 0) {
            throw new IllegalArgumentException("level must be positive");
        }
        this.level = level;
    }

    @Override
    public boolean allows(ActivityAccessContext context) {
        return context.participant().level() >= level;
    }
}
