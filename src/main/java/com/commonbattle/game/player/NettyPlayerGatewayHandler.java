package com.commonbattle.game.player;

import com.commonbattle.game.session.NettyPlayerOutboundWriter;
import com.commonbattle.game.session.PlayerClientConnectionResult;
import com.commonbattle.game.session.PlayerClientConnectionService;
import com.commonbattle.game.session.PlayerClientEnvelope;
import com.commonbattle.game.session.PlayerClientErrorCode;
import com.commonbattle.game.session.PlayerClientHeartbeat;
import com.commonbattle.game.session.PlayerClientHeartbeatAck;
import com.commonbattle.game.session.PlayerClientInboundEnvelope;
import com.commonbattle.game.session.PlayerClientLoginRequest;
import com.commonbattle.game.session.PlayerClientLoginResponse;
import com.commonbattle.game.session.PlayerClientOutboundAck;
import com.commonbattle.game.session.PlayerClientRejectResponse;
import com.commonbattle.game.session.PlayerOutboundAckStatus;
import com.commonbattle.game.session.PlayerSession;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;

import java.time.Clock;
import java.util.Objects;

/**
 * 玩家客户端 Netty 网关 handler。
 * 登录只完成 Agent 装载和连接绑定；业务命令只投递到入站接收口，业务逻辑仍在玩家 Actor mailbox 内执行。
 */
public final class NettyPlayerGatewayHandler extends SimpleChannelInboundHandler<PlayerClientInboundEnvelope> {
    public static final String LOGIN_RESPONSE_TOPIC = "player.login.response";
    public static final String HEARTBEAT_ACK_TOPIC = "player.heartbeat.ack";
    public static final String REJECT_TOPIC = "player.gateway.reject";

    private static final AttributeKey<PlayerSession> SESSION = AttributeKey.valueOf("commonbattle.player.session");

    private final PlayerClientConnectionService connections;
    private final PlayerClientCommandAcceptor commands;
    private final Clock clock;
    private final NettyPlayerGatewayMetrics metrics;
    private final PlayerClientAuthenticator authenticator;
    private final PlayerGatewayConfig config;
    private final NettyPlayerGatewayConnectionIndex connectionIndex;
    private final PlayerGatewayRateLimiter commandRateLimiter;
    private final PlayerGatewayRateLimiter heartbeatRateLimiter;

    public NettyPlayerGatewayHandler(
            PlayerClientConnectionService connections,
            PlayerClientCommandAcceptor commands,
            Clock clock
    ) {
        this(connections, commands, clock, new NettyPlayerGatewayMetrics(),
                PlayerClientAuthenticator.allowAll(), PlayerGatewayConfig.defaults(),
                new NettyPlayerGatewayConnectionIndex());
    }

    NettyPlayerGatewayHandler(
            PlayerClientConnectionService connections,
            PlayerClientCommandAcceptor commands,
            Clock clock,
            NettyPlayerGatewayMetrics metrics,
            PlayerClientAuthenticator authenticator,
            PlayerGatewayConfig config,
            NettyPlayerGatewayConnectionIndex connectionIndex
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.config = Objects.requireNonNull(config, "config");
        this.connectionIndex = Objects.requireNonNull(connectionIndex, "connectionIndex");
        this.commandRateLimiter = new PlayerGatewayRateLimiter(config.commandRateLimit(), clock);
        this.heartbeatRateLimiter = new PlayerGatewayRateLimiter(config.heartbeatRateLimit(), clock);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, PlayerClientInboundEnvelope envelope) {
        switch (envelope.kind()) {
            case PlayerClientInboundEnvelope.LOGIN -> login(context, (PlayerClientLoginRequest) envelope.payload());
            case PlayerClientInboundEnvelope.COMMAND -> command(context, envelope);
            case PlayerClientInboundEnvelope.HEARTBEAT -> heartbeat(context, envelope);
            case PlayerClientInboundEnvelope.OUTBOUND_ACK -> outboundAck(context, envelope);
            default -> {
                metrics.invalidFrame();
                context.close();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        PlayerSession session = context.channel().attr(SESSION).getAndSet(null);
        if (session != null) {
            connectionIndex.unbind(session, context.channel());
            connections.disconnect(session);
            metrics.disconnectedSession();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        metrics.invalidFrame();
        context.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            metrics.idleTimeout();
            context.close();
            return;
        }
        super.userEventTriggered(context, event);
    }

    public NettyPlayerGatewayStats stats() {
        return metrics.snapshot();
    }

    private void login(ChannelHandlerContext context, PlayerClientLoginRequest request) {
        try {
            disconnectCurrentSession(context);
            PlayerClientAuthResult auth = authenticator.authenticate(request, context.channel().remoteAddress());
            if (!auth.accepted()) {
                metrics.authRejectedLogin();
                metrics.failedLogin();
                failedLogin(context, request, PlayerClientErrorCode.AUTH_REJECTED,
                        auth.message().isBlank() ? "authentication rejected" : auth.message());
                return;
            }
            if (config.duplicateLoginPolicy() == PlayerGatewayDuplicateLoginPolicy.REJECT_NEW
                    && connectionIndex.hasActive(request.playerId())) {
                metrics.duplicateRejectedLogin();
                metrics.failedLogin();
                failedLogin(context, request, PlayerClientErrorCode.DUPLICATE_LOGIN, "duplicate login rejected");
                return;
            }
            PlayerClientConnectionResult result = connections.connect(
                    request.playerId(),
                    request.sessionId(),
                    new NettyPlayerOutboundWriter(context.channel())
            );
            PlayerSession session = result.login().session();
            context.channel().attr(SESSION).set(session);
            io.netty.channel.Channel oldChannel = connectionIndex.bind(session, context.channel());
            context.writeAndFlush(new PlayerClientEnvelope(
                    session.playerId(),
                    LOGIN_RESPONSE_TOPIC,
                    PlayerClientLoginResponse.success(result.login()),
                    session.epoch(),
                    clock.instant()
            ));
            if (oldChannel != null) {
                metrics.kickedConnection();
                rejectAndClose(oldChannel, session.playerId(), session.epoch(), PlayerClientErrorCode.DUPLICATE_LOGIN,
                        "duplicate login kicked old connection");
            }
            metrics.acceptedLogin();
        } catch (RuntimeException e) {
            metrics.failedLogin();
            failedLogin(context, request, PlayerClientErrorCode.LOGIN_FAILED, e.getMessage());
        }
    }

    private void failedLogin(
            ChannelHandlerContext context,
            PlayerClientLoginRequest request,
            PlayerClientErrorCode code,
            String message
    ) {
        context.writeAndFlush(new PlayerClientEnvelope(
                request.playerId(),
                LOGIN_RESPONSE_TOPIC,
                PlayerClientLoginResponse.failed(request, code, message),
                1,
                clock.instant()
        )).addListener(ignored -> context.close());
    }

    private void disconnectCurrentSession(ChannelHandlerContext context) {
        PlayerSession previous = context.channel().attr(SESSION).getAndSet(null);
        if (previous == null) {
            return;
        }
        connectionIndex.unbind(previous, context.channel());
        connections.disconnect(previous);
        metrics.disconnectedSession();
    }

    private void command(ChannelHandlerContext context, PlayerClientInboundEnvelope envelope) {
        Object payload = envelope.payload();
        if (!(payload instanceof com.commonbattle.game.session.PlayerClientCommandEnvelope command)) {
            metrics.invalidFrame();
            rejectCurrentOrClose(context, PlayerClientErrorCode.INVALID_PAYLOAD, "invalid command payload");
            return;
        }
        PlayerSession session = context.channel().attr(SESSION).get();
        if (session == null) {
            metrics.rejectedCommand();
            rejectAndClose(context, command.playerId(), command.sequence(),
                    PlayerClientErrorCode.NOT_LOGGED_IN, "command requires login");
            return;
        }
        if (session.playerId() != command.playerId()
                || !session.sessionId().equals(command.sessionId())
                || session.epoch() != command.sessionEpoch()) {
            metrics.rejectedCommand();
            rejectAndClose(context, command.playerId(), command.sequence(),
                    PlayerClientErrorCode.SESSION_EXPIRED, "stale player session");
            return;
        }
        if (rejectSlowClient(context, session, command.sequence())) {
            return;
        }
        if (!commandRateLimiter.tryAcquire()) {
            metrics.rateLimitedCommand();
            rejectAndClose(context, command.playerId(), command.sequence(),
                    PlayerClientErrorCode.COMMAND_RATE_LIMITED, "command rate limited");
            return;
        }
        commands.accept(command);
        metrics.acceptedCommand();
    }

    private void heartbeat(ChannelHandlerContext context, PlayerClientInboundEnvelope envelope) {
        Object payload = envelope.payload();
        if (!(payload instanceof PlayerClientHeartbeat heartbeat)) {
            metrics.invalidFrame();
            rejectCurrentOrClose(context, PlayerClientErrorCode.INVALID_PAYLOAD, "invalid heartbeat payload");
            return;
        }
        PlayerSession session = context.channel().attr(SESSION).get();
        if (session == null) {
            metrics.rejectedHeartbeat();
            rejectAndClose(context, heartbeat.playerId(), heartbeat.sequence(),
                    PlayerClientErrorCode.NOT_LOGGED_IN, "heartbeat requires login");
            return;
        }
        if (session.playerId() != heartbeat.playerId()
                || !session.sessionId().equals(heartbeat.sessionId())
                || session.epoch() != heartbeat.sessionEpoch()) {
            metrics.rejectedHeartbeat();
            rejectAndClose(context, heartbeat.playerId(), heartbeat.sequence(),
                    PlayerClientErrorCode.SESSION_EXPIRED, "stale player session");
            return;
        }
        if (rejectSlowClient(context, session, heartbeat.sequence())) {
            return;
        }
        if (!heartbeatRateLimiter.tryAcquire()) {
            metrics.rateLimitedHeartbeat();
            rejectAndClose(context, heartbeat.playerId(), heartbeat.sequence(),
                    PlayerClientErrorCode.HEARTBEAT_RATE_LIMITED, "heartbeat rate limited");
            return;
        }
        metrics.acceptedHeartbeat();
        if (config.heartbeatAckEnabled()) {
            context.writeAndFlush(new PlayerClientEnvelope(
                    session.playerId(),
                    HEARTBEAT_ACK_TOPIC,
                    new PlayerClientHeartbeatAck(
                            heartbeat.playerId(),
                            heartbeat.sessionId(),
                            heartbeat.sessionEpoch(),
                            heartbeat.sequence(),
                            "OK"
                    ),
                    heartbeat.sequence(),
                    clock.instant()
            ));
        }
    }

    private void outboundAck(ChannelHandlerContext context, PlayerClientInboundEnvelope envelope) {
        Object payload = envelope.payload();
        if (!(payload instanceof PlayerClientOutboundAck ack)) {
            metrics.invalidFrame();
            rejectCurrentOrClose(context, PlayerClientErrorCode.INVALID_PAYLOAD, "invalid outbound ack payload");
            return;
        }
        PlayerSession session = context.channel().attr(SESSION).get();
        if (session == null) {
            metrics.rejectedAck();
            rejectAndClose(context, ack.playerId(), ack.acknowledgedSequence(),
                    PlayerClientErrorCode.NOT_LOGGED_IN, "outbound ack requires login");
            return;
        }
        if (session.playerId() != ack.playerId()
                || !session.sessionId().equals(ack.sessionId())
                || session.epoch() != ack.sessionEpoch()) {
            metrics.rejectedAck();
            rejectAndClose(context, ack.playerId(), ack.acknowledgedSequence(),
                    PlayerClientErrorCode.SESSION_EXPIRED, "stale player session");
            return;
        }
        connections.acknowledge(session, ack.acknowledgedSequence());
        metrics.acceptedAck();
    }

    private boolean rejectSlowClient(ChannelHandlerContext context, PlayerSession session, long sequence) {
        if (!config.closeSlowClient()) {
            return false;
        }
        PlayerOutboundAckStatus status = connections.ackStatus(
                session,
                config.maxPendingAckMessages(),
                config.maxPendingAckAge()
        );
        if (!status.slow()) {
            return false;
        }
        metrics.slowClientClosure();
        rejectAndClose(context, session.playerId(), sequence, PlayerClientErrorCode.SLOW_CLIENT,
                "slow client pendingAck=" + status.pendingMessages()
                        + ", oldestPendingAckAgeMillis=" + status.oldestPendingAgeMillis());
        return true;
    }

    private void rejectCurrentOrClose(
            ChannelHandlerContext context,
            PlayerClientErrorCode code,
            String message
    ) {
        PlayerSession session = context.channel().attr(SESSION).get();
        if (session == null) {
            context.close();
            return;
        }
        rejectAndClose(context, session.playerId(), session.epoch(), code, message);
    }

    private void rejectAndClose(
            ChannelHandlerContext context,
            long playerId,
            long sequence,
            PlayerClientErrorCode code,
            String message
    ) {
        rejectAndClose(context.channel(), playerId, sequence, code, message);
    }

    private void rejectAndClose(
            Channel channel,
            long playerId,
            long sequence,
            PlayerClientErrorCode code,
            String message
    ) {
        channel.writeAndFlush(new PlayerClientEnvelope(
                playerId,
                REJECT_TOPIC,
                new PlayerClientRejectResponse(code, message, true),
                Math.max(1, sequence),
                clock.instant()
        )).addListener(ignored -> channel.close());
    }
}
