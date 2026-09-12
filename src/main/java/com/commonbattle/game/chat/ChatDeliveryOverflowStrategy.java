package com.commonbattle.game.chat;

/**
 * 玩家待投递队列满时的处理策略。
 */
public enum ChatDeliveryOverflowStrategy {
    DROP_OLDEST,
    DROP_NEWEST
}
