package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundDeliveryResult;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;

import java.util.Objects;
import java.util.Set;

/**
 * 基于通用玩家出站投递层的推送端口实现。
 * Game、Scene、Chat 等服务可复用该实现，业务模块不直接依赖 Netty 写出细节。
 */
public final class OutboundPlayerPushPort implements PlayerPushPort {
    private final PlayerOutboundDeliveryHub outbound;
    private final PlayerOutboundTopicPolicies topicPolicies;

    public OutboundPlayerPushPort(PlayerOutboundDeliveryHub outbound) {
        this(outbound, PlayerOutboundTopicPolicies.gameDefaults());
    }

    public OutboundPlayerPushPort(PlayerOutboundDeliveryHub outbound, PlayerOutboundTopicPolicies topicPolicies) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.topicPolicies = Objects.requireNonNull(topicPolicies, "topicPolicies");
    }

    @Override
    public PlayerOutboundDeliveryResult push(Set<Long> recipients, String topic, Object payload) {
        return outbound.deliver(topicPolicies.envelope(recipients, topic, payload));
    }
}
