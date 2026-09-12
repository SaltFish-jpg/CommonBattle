package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端连接绑定结果。
 */
public record PlayerClientConnectionResult(
        PlayerLoginResult login,
        PlayerOutboundDeliveryResult offlineFlush
) {
    public PlayerClientConnectionResult {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(offlineFlush, "offlineFlush");
    }
}
