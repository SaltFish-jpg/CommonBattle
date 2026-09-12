package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientCommandResponse;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundEnvelope;

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

    public PlayerClientCommandIngress(PlayerBusinessCommandGateway commands, PlayerOutboundDeliveryHub outbound) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
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
                outbound.deliver(new PlayerOutboundEnvelope(
                        Set.of(envelope.playerId()),
                        RESPONSE_TOPIC,
                        PlayerClientCommandResponse.from(response, replayed)
                ));
            }
        });
    }
}
