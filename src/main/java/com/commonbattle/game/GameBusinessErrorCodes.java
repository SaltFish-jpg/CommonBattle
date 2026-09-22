package com.commonbattle.game;

/**
 * 游戏通用样例业务的稳定失败码。
 * 这些值面向客户端、RPC 调用方和观测系统，新增时应保持向后兼容。
 */
public final class GameBusinessErrorCodes {
    public static final String BAG_UNKNOWN_ITEM = "BAG_UNKNOWN_ITEM";
    public static final String BAG_NOT_STACKABLE = "BAG_NOT_STACKABLE";
    public static final String BAG_NOT_ENOUGH_ITEM = "BAG_NOT_ENOUGH_ITEM";

    public static final String ACTIVITY_UNKNOWN = "ACTIVITY_UNKNOWN";
    public static final String ACTIVITY_WRONG_TYPE = "ACTIVITY_WRONG_TYPE";
    public static final String ACTIVITY_INVALID_DELTA = "ACTIVITY_INVALID_DELTA";
    public static final String ACTIVITY_REWARD_ALREADY_CLAIMED = "ACTIVITY_REWARD_ALREADY_CLAIMED";
    public static final String ACTIVITY_REWARD_NOT_READY = "ACTIVITY_REWARD_NOT_READY";
    public static final String ACTIVITY_NOT_OPEN = "ACTIVITY_NOT_OPEN";
    public static final String ACTIVITY_NOT_ELIGIBLE = "ACTIVITY_NOT_ELIGIBLE";

    public static final String GROWTH_INVALID_EXP_ITEM_COUNT = "GROWTH_INVALID_EXP_ITEM_COUNT";

    public static final String BATTLE_STAMINA_NOT_ENOUGH = "BATTLE_STAMINA_NOT_ENOUGH";

    private GameBusinessErrorCodes() {
    }
}
