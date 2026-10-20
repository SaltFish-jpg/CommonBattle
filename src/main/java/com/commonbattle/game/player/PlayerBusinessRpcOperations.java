package com.commonbattle.game.player;

/**
 * 玩家业务命令跨服转发使用的稳定 RPC 操作名。
 */
public final class PlayerBusinessRpcOperations {
    public static final String DISPATCH = "player.business.dispatch";
    public static final String GET_RESULT = "player.business.get_result";

    private PlayerBusinessRpcOperations() {
    }
}
