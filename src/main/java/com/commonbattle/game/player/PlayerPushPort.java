package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerOutboundDeliveryResult;

import java.util.Set;

/**
 * 玩家业务向客户端推送消息的统一端口。
 * 业务代码只声明接收玩家、topic 和 payload，具体可靠性由出站 topic 策略决定。
 */
public interface PlayerPushPort {
    PlayerPushPort NOOP = (recipients, topic, payload) -> PlayerOutboundDeliveryResult.empty();

    PlayerOutboundDeliveryResult push(Set<Long> recipients, String topic, Object payload);

    default PlayerOutboundDeliveryResult push(long playerId, String topic, Object payload) {
        return push(Set.of(playerId), topic, payload);
    }
}
