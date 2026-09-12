package com.commonbattle.game.player;

/**
 * 玩家客户端重复登录策略。
 */
public enum PlayerGatewayDuplicateLoginPolicy {
    KICK_OLD,
    REJECT_NEW
}
