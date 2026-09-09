package com.commonbattle.game.player;

/**
 * 一次玩家业务响应等待注册。
 * 调用方在命令没有进入本地邮箱或改走远端 RPC 时，应主动取消等待。
 */
@FunctionalInterface
public interface PlayerBusinessResponseRegistration {
    PlayerBusinessResponseRegistration NOOP = () -> {
    };

    void cancel();
}
