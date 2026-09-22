package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientCommandResponse;
import com.commonbattle.game.session.PlayerClientErrorCode;
import com.commonbattle.game.session.PlayerCommandStatus;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    public PlayerClientCommandAcceptResult accept(PlayerClientCommandEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        AtomicBoolean returned = new AtomicBoolean();
        AtomicReference<PlayerClientCommandAcceptResult> synchronousReject = new AtomicReference<>();
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
                if (!returned.get()) {
                    PlayerClientCommandAcceptResult rejected = toSynchronousIngressReject(response);
                    if (rejected != null) {
                        synchronousReject.compareAndSet(null, rejected);
                    }
                }
            }
        });
        returned.set(true);
        PlayerClientCommandAcceptResult rejected = synchronousReject.get();
        return rejected == null ? PlayerClientCommandAcceptResult.acceptedResult() : rejected;
    }

    private static PlayerClientCommandAcceptResult toSynchronousIngressReject(PlayerBusinessResponse response) {
        if (response.status() != PlayerBusinessResponseStatus.FAILED) {
            return null;
        }
        PlayerCommandStatus status;
        try {
            status = PlayerCommandStatus.valueOf(response.code());
        } catch (IllegalArgumentException e) {
            return toSynchronousRemoteReject(response);
        }
        PlayerClientErrorCode code = switch (status) {
            case RATE_LIMITED -> PlayerClientErrorCode.COMMAND_RATE_LIMITED;
            case BACKPRESSURED, MAILBOX_FULL, AGENT_MIGRATING -> PlayerClientErrorCode.COMMAND_BACKPRESSURED;
            case STALE_SESSION -> PlayerClientErrorCode.SESSION_EXPIRED;
            case GAP, UNKNOWN_OPERATION -> PlayerClientErrorCode.INVALID_PAYLOAD;
            case DRAINING, AGENT_MISSING -> PlayerClientErrorCode.COMMAND_INGRESS_FAILED;
            case ACCEPTED, DUPLICATE, ROUTED_REMOTE -> PlayerClientErrorCode.OK;
        };
        if (code == PlayerClientErrorCode.OK) {
            return null;
        }
        return PlayerClientCommandAcceptResult.rejectedWithoutClosing(
                code,
                response.message(),
                java.time.Duration.ofMillis(response.retryAfterMillis())
        );
    }

    private static PlayerClientCommandAcceptResult toSynchronousRemoteReject(PlayerBusinessResponse response) {
        PlayerClientErrorCode code = switch (response.code()) {
            case PlayerBusinessResponse.REMOTE_TIMEOUT -> PlayerClientErrorCode.COMMAND_REMOTE_TIMEOUT;
            case PlayerBusinessResponse.REMOTE_UNAVAILABLE -> PlayerClientErrorCode.COMMAND_REMOTE_UNAVAILABLE;
            case PlayerBusinessResponse.REMOTE_REJECTED -> PlayerClientErrorCode.COMMAND_REMOTE_REJECTED;
            case PlayerBusinessResponse.REMOTE_DRAINING -> PlayerClientErrorCode.COMMAND_REMOTE_DRAINING;
            case PlayerBusinessResponse.REMOTE_CIRCUIT_OPEN -> PlayerClientErrorCode.COMMAND_REMOTE_CIRCUIT_OPEN;
            default -> null;
        };
        if (code == null) {
            return null;
        }
        return PlayerClientCommandAcceptResult.rejectedWithoutClosing(
                code,
                response.message(),
                java.time.Duration.ofMillis(response.retryAfterMillis())
        );
    }
}
