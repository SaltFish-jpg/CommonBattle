package com.commonbattle.game.session;

import java.util.Objects;

/**
 * 玩家客户端入站统一信封。
 */
public record PlayerClientInboundEnvelope(String kind, Object payload) {
    public static final String LOGIN = "login";
    public static final String COMMAND = "command";
    public static final String HEARTBEAT = "heartbeat";
    public static final String OUTBOUND_ACK = "outbound.ack";

    public PlayerClientInboundEnvelope {
        kind = Objects.requireNonNull(kind, "kind").trim();
        Objects.requireNonNull(payload, "payload");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }

    public static PlayerClientInboundEnvelope login(PlayerClientLoginRequest request) {
        return new PlayerClientInboundEnvelope(LOGIN, request);
    }

    public static PlayerClientInboundEnvelope command(PlayerClientCommandEnvelope command) {
        return new PlayerClientInboundEnvelope(COMMAND, command);
    }

    public static PlayerClientInboundEnvelope heartbeat(PlayerClientHeartbeat heartbeat) {
        return new PlayerClientInboundEnvelope(HEARTBEAT, heartbeat);
    }

    public static PlayerClientInboundEnvelope outboundAck(PlayerClientOutboundAck ack) {
        return new PlayerClientInboundEnvelope(OUTBOUND_ACK, ack);
    }
}
