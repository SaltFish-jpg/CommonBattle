package com.commonbattle.example.cross.scene;

import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryResult;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;

import java.util.Objects;

/**
 * 将 Scene 广播事件适配到通用玩家出站投递中心。
 */
public final class PlayerOutboundSceneBroadcastSink implements SceneBroadcastSink {
    private final PlayerOutboundDeliveryHub deliveryHub;
    private final PlayerOutboundTopicPolicies topicPolicies;

    public PlayerOutboundSceneBroadcastSink(PlayerOutboundDeliveryHub deliveryHub) {
        this(deliveryHub, PlayerOutboundTopicPolicies.gameDefaults());
    }

    public PlayerOutboundSceneBroadcastSink(
            PlayerOutboundDeliveryHub deliveryHub,
            PlayerOutboundTopicPolicies topicPolicies
    ) {
        this.deliveryHub = Objects.requireNonNull(deliveryHub, "deliveryHub");
        this.topicPolicies = Objects.requireNonNull(topicPolicies, "topicPolicies");
    }

    @Override
    public PlayerOutboundDeliveryResult publish(SceneBroadcastEnvelope envelope) {
        return deliveryHub.deliver(topicPolicies.envelope(
                envelope.recipients(),
                PlayerOutboundTopicPolicies.SCENE_BROADCAST,
                envelope.event()
        ));
    }
}
