package com.commonbattle.game.player;

/**
 * 示例玩家业务操作名。
 * 网关层使用这些稳定字符串注册 PlayerCommandDispatcher 的处理器。
 */
public final class PlayerBusinessOperations {
    public static final String ACTIVITY_PROGRESS = "activity.progress";
    public static final String ACTIVITY_CLAIM = "activity.claim";
    public static final String GROWTH_USE_EXP_ITEMS = "growth.useExpItems";
    public static final String SHOP_BUY = "shop.buy";
    public static final String BATTLE_CLEAR_STAGE = "battle.clearStage";
    public static final String BATTLE_SWEEP_STAGE = "battle.sweepStage";
    public static final String TASK_CLAIM = "task.claim";
    public static final String ACHIEVEMENT_CLAIM = "achievement.claim";

    private PlayerBusinessOperations() {
    }
}
