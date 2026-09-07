package com.commonbattle.game.player;

/**
 * 统一业务失败信封向旧 failed 回调适配时使用的异常。
 */
public final class PlayerBusinessResponseException extends RuntimeException {
    private final PlayerBusinessResponse response;

    public PlayerBusinessResponseException(PlayerBusinessResponse response) {
        super(response.message());
        this.response = response;
    }

    public PlayerBusinessResponse response() {
        return response;
    }
}
