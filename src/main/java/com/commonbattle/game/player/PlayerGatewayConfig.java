package com.commonbattle.game.player;

import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * 玩家客户端网关配置。
 */
public record PlayerGatewayConfig(
        Duration readerIdleTimeout,
        boolean heartbeatAckEnabled,
        PlayerGatewayDuplicateLoginPolicy duplicateLoginPolicy,
        AgentRateLimitPolicy commandRateLimit,
        AgentRateLimitPolicy heartbeatRateLimit,
        int maxPendingAckMessages,
        Duration maxPendingAckAge,
        boolean closeSlowClient
) {
    public PlayerGatewayConfig(Duration readerIdleTimeout, boolean heartbeatAckEnabled) {
        this(readerIdleTimeout, heartbeatAckEnabled, PlayerGatewayDuplicateLoginPolicy.KICK_OLD,
                AgentRateLimitPolicy.perSecond(200, 200), AgentRateLimitPolicy.perSecond(60, 60),
                512, Duration.ofSeconds(30), true);
    }

    public PlayerGatewayConfig {
        readerIdleTimeout = Objects.requireNonNull(readerIdleTimeout, "readerIdleTimeout");
        duplicateLoginPolicy = Objects.requireNonNull(duplicateLoginPolicy, "duplicateLoginPolicy");
        commandRateLimit = Objects.requireNonNull(commandRateLimit, "commandRateLimit");
        heartbeatRateLimit = Objects.requireNonNull(heartbeatRateLimit, "heartbeatRateLimit");
        maxPendingAckAge = Objects.requireNonNull(maxPendingAckAge, "maxPendingAckAge");
        if (readerIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("readerIdleTimeout must not be negative");
        }
        if (maxPendingAckMessages <= 0) {
            throw new IllegalArgumentException("maxPendingAckMessages must be positive");
        }
        if (maxPendingAckAge.isNegative()) {
            throw new IllegalArgumentException("maxPendingAckAge must not be negative");
        }
    }

    public static PlayerGatewayConfig defaults() {
        return new PlayerGatewayConfig(Duration.ZERO, true);
    }
}
