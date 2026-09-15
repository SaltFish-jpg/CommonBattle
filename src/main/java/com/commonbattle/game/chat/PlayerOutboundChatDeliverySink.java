package com.commonbattle.game.chat;

import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryResult;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;

import java.util.Objects;

/**
 * 将 Chat fanout 适配到通用玩家出站投递层。
 */
public final class PlayerOutboundChatDeliverySink implements ChatDeliverySink {
    public static final String TOPIC = "chat.delivery";

    private final PlayerOutboundDeliveryHub deliveryHub;
    private final PlayerOutboundTopicPolicies topicPolicies;

    public PlayerOutboundChatDeliverySink(PlayerOutboundDeliveryHub deliveryHub) {
        this(deliveryHub, PlayerOutboundTopicPolicies.gameDefaults());
    }

    public PlayerOutboundChatDeliverySink(
            PlayerOutboundDeliveryHub deliveryHub,
            PlayerOutboundTopicPolicies topicPolicies
    ) {
        this.deliveryHub = Objects.requireNonNull(deliveryHub, "deliveryHub");
        this.topicPolicies = Objects.requireNonNull(topicPolicies, "topicPolicies");
    }

    @Override
    public ChatDeliveryResult deliver(ChatDeliveryEnvelope envelope) {
        PlayerOutboundDeliveryResult result = deliveryHub.deliver(topicPolicies.envelope(
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
