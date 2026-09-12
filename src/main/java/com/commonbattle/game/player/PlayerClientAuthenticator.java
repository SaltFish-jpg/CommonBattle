package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientLoginRequest;

import java.net.SocketAddress;

/**
 * 玩家客户端登录鉴权扩展点。
 * 生产环境可在这里接入账号服票据、设备风控或灰度准入，网关仍只在鉴权通过后装载玩家 Agent。
 */
@FunctionalInterface
public interface PlayerClientAuthenticator {
    PlayerClientAuthResult authenticate(PlayerClientLoginRequest request, SocketAddress remoteAddress);

    static PlayerClientAuthenticator allowAll() {
        return (request, remoteAddress) -> PlayerClientAuthResult.allow();
    }
}
