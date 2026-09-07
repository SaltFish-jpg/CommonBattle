package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommand;

/**
 * 玩家业务命令返回投递口。
 * 生产环境通常在这里把结果编码成网关回包；测试或离线任务可记录到内存。
 */
public interface PlayerBusinessResultSink {
    default void completed(PlayerCommand command, PlayerBusinessResponse response) {
        if (response.succeeded()) {
            succeeded(command, response.payload());
        } else {
            failed(command, new PlayerBusinessResponseException(response));
        }
    }

    void succeeded(PlayerCommand command, Object response);

    void failed(PlayerCommand command, Throwable error);

    PlayerBusinessResultSink NOOP = new PlayerBusinessResultSink() {
        @Override
        public void succeeded(PlayerCommand command, Object response) {
        }

        @Override
        public void failed(PlayerCommand command, Throwable error) {
        }
    };
}
