package com.commonbattle.game.activity;

/**
 * 永远允许参与的活动条件。
 */
public final class AlwaysParticipationCondition implements ParticipationCondition {
    public static final AlwaysParticipationCondition INSTANCE = new AlwaysParticipationCondition();

    private AlwaysParticipationCondition() {
    }

    @Override
    public boolean allows(ActivityAccessContext context) {
        return true;
    }
}
