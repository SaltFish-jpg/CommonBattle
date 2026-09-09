package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

/**
 * 可延迟完成响应的玩家业务命令。
 * 用于跨服 RPC、DB、缓存等异步依赖：发起调用后释放当前 Actor 线程，回调重新投递到玩家 mailbox 后再完成响应。
 */
public interface AsyncPlayerBusinessCommand<R> extends PlayerBusinessCommand<R> {
    @Override
    default R execute(PlayerGameExecution execution) {
        throw new UnsupportedOperationException("async player business command must be executed with response sink");
    }

    void executeAsync(PlayerGameAgent agent, PlayerCommand command, PlayerBusinessResultSink results);
}
