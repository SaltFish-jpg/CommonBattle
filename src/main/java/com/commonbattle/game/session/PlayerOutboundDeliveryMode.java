package com.commonbattle.game.session;

/**
 * 玩家出站消息投递语义。
 * 关键结算、命令回包用 RELIABLE；聊天红点、飘字等可用 BEST_EFFORT；高频状态快照用 COALESCING。
 */
public enum PlayerOutboundDeliveryMode {
    RELIABLE,
    BEST_EFFORT,
    COALESCING
}
