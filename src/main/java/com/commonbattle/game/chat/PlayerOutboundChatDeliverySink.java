package com.commonbattle.game.chat;

import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryResult;
import com.commonbattle.game.session.PlayerOutboundEnvelope;

import java.util.Objects;

/**
 * 将 Chat fanout 适配到通用玩家出站投递层。
 */
public final class PlayerOutboundChatDeliverySink implements ChatDeliverySink {
    public static final String TOPIC = "chat.delivery";

    private final PlayerOutboundDeliveryHub deliveryHub;

    public PlayerOutboundChatDeliverySink(PlayerOutboundDeliveryHub deliveryHub) {
        this.deliveryHub = Objects.requireNonNull(deliveryHub, "deliveryHub");
    }

    @Override
    public ChatDeliveryResult deliver(ChatDeliveryEnvelope envelope) {
        PlayerOutboundDeliveryResult result = deliveryHub.deliver(new PlayerOutboundEnvelope(
                envelope.recipients(),
                TOPIC,
                envelope.delivery()
        ));
        return new ChatDeliveryResult(
                result.onlineDeliveries() + result.offlineQueuedDeliveries(),
                result.droppedDeliveries(),
                result.failedOnlineDeliveries()
        );
    }
}
