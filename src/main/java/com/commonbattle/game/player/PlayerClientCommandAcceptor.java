package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;

/**
 * 玩家客户端命令入站接收口。
 */
@FunctionalInterface
public interface PlayerClientCommandAcceptor {
    PlayerClientCommandAcceptResult accept(PlayerClientCommandEnvelope envelope);
}
