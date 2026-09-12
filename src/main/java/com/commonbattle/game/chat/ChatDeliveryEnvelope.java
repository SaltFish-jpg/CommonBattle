package com.commonbattle.game.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Chat fanout 投递信封。
 * 频道 Actor 生成有序消息后，用该信封交给投递层处理在线玩家网关、场景广播或离线盒子。
 */
public record ChatDeliveryEnvelope(String channelId, Set<Long> recipients, ChatDelivery delivery) {
    public ChatDeliveryEnvelope {
        channelId = ChatJoinRequest.normalizeChannelId(channelId);
        recipients = Set.copyOf(Objects.requireNonNull(recipients, "recipients"));
        Objects.requireNonNull(delivery, "delivery");
    }
}
