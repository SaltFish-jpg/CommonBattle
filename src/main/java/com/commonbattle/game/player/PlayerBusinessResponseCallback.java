package com.commonbattle.game.player;

/**
 * 玩家业务命令完成后的统一响应回调。
 * 本服 ask 和跨服 RPC endpoint 都通过它等待玩家邮箱内的真实执行结果。
 */
@FunctionalInterface
public interface PlayerBusinessResponseCallback {
    void completed(PlayerBusinessResponse response);
}
