package com.commonbattle.game.player;

import java.util.Objects;

/**
 * 玩家业务响应投递结果。
 */
public record PlayerBusinessResponseDelivery(PlayerBusinessResponse response, boolean replayed) {
    public PlayerBusinessResponseDelivery {
        Objects.requireNonNull(response, "response");
    }
}
