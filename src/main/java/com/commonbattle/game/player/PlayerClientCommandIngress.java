package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientCommandResponse;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;

import java.util.Objects;
import java.util.Set;

/**
 * 玩家客户端命令入站门面。
 * Netty 线程调用它只会提交命令和登记回调，业务响应仍由玩家 Actor 完成后投递到玩家出站层。
 */
public final class PlayerClientCommandIngress implements PlayerClientCommandAcceptor {
    public static final String RESPONSE_TOPIC = "player.command.response";

    private final PlayerBusinessCommandGateway commands;
    private final PlayerOutboundDeliveryHub outbound;
    private final PlayerOutboundTopicPolicies topicPolicies;

    public PlayerClientCommandIngress(PlayerBusinessCommandGateway commands, PlayerOutboundDeliveryHub outbound) {
        this(commands, outbound, PlayerOutboundTopicPolicies.gameDefaults());
    }

    public PlayerClientCommandIngress(
            PlayerBusinessCommandGateway commands,
            PlayerOutboundDeliveryHub outbound,
            PlayerOutboundTopicPolicies topicPolicies
    ) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.topicPolicies = Objects.requireNonNull(topicPolicies, "topicPolicies");
    }

    public void accept(PlayerClientCommandEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        commands.submitDetailed(envelope.toCommand(), new PlayerBusinessResponseCallback() {
            @Override
            public void completed(PlayerBusinessResponse response) {
                completed(response, false);
            }

            @Override
            public void completed(PlayerBusinessResponse response, boolean replayed) {
                outbound.deliver(topicPolicies.envelope(
                        Set.of(envelope.playerId()),
                        RESPONSE_TOPIC,
                        PlayerClientCommandResponse.from(response, replayed)
                ));
            }
        });
    }
}
