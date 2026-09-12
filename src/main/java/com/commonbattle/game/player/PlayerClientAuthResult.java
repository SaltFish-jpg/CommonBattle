package com.commonbattle.game.player;

import java.util.Objects;

/**
 * 玩家客户端登录鉴权结果。
 */
public record PlayerClientAuthResult(boolean accepted, String message) {
    public PlayerClientAuthResult {
        message = Objects.requireNonNullElse(message, "").trim();
    }

    public static PlayerClientAuthResult allow() {
        return new PlayerClientAuthResult(true, "");
    }

    public static PlayerClientAuthResult rejected(String message) {
        return new PlayerClientAuthResult(false, message);
    }
}
