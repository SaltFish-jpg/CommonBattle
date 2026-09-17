package com.commonbattle.game.player;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.NettyPlayerClientFrameEncoder;
import com.commonbattle.game.session.NettyPlayerClientInboundFrameDecoder;
import com.commonbattle.game.session.PlayerClientConnectionService;
import com.commonbattle.game.session.PlayerClientErrorCode;
import com.commonbattle.game.session.PlayerClientEnvelope;
import com.commonbattle.game.session.PlayerClientHeartbeat;
import com.commonbattle.game.session.PlayerClientHeartbeatAck;
import com.commonbattle.game.session.PlayerClientInboundEnvelope;
import com.commonbattle.game.session.PlayerClientLoginRequest;
import com.commonbattle.game.session.PlayerClientLoginResponse;
import com.commonbattle.game.session.PlayerClientOutboundAck;
import com.commonbattle.game.session.PlayerClientPayloadCodecs;
import com.commonbattle.game.session.PlayerClientRejectResponse;
import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerLoginService;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundEnvelope;
import com.commonbattle.game.session.ProtoPlayerClientCodec;
import com.commonbattle.game.session.ProtoPlayerClientInboundCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyPlayerGatewayHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void loginFrameBindsSessionAndWritesLoginResponse() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));

        PlayerClientEnvelope envelope = fixture.readOutbound(channel);
        PlayerClientLoginResponse response = assertInstanceOf(PlayerClientLoginResponse.class, envelope.payload());
        assertEquals(NettyPlayerGatewayHandler.LOGIN_RESPONSE_TOPIC, envelope.topic());
        assertEquals(10001L, response.playerId());
        assertEquals("session-1", response.sessionId());
        assertEquals(1, response.sessionEpoch());
        assertEquals("OK", response.status());
        assertEquals(PlayerClientErrorCode.OK, response.code());
        assertEquals(1, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(1, fixture.stats().acceptedLogins());
    }

    @Test
    void commandAfterLoginIsForwardedToIngress() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        PlayerClientCommandEnvelope command = new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                1,
                1,
                "test.echo",
                "payload"
        );
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.command(command)));

        assertEquals(List.of(command), fixture.commands);
        assertEquals(1, fixture.stats().acceptedCommands());
        assertTrue(channel.isOpen());
    }

    @Test
    void heartbeatAfterLoginWritesAckWithoutBusinessCommand() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.heartbeat(
                new PlayerClientHeartbeat(10001L, "session-1", 1, 2)
        )));

        PlayerClientEnvelope envelope = fixture.readOutbound(channel);
        PlayerClientHeartbeatAck ack = assertInstanceOf(PlayerClientHeartbeatAck.class, envelope.payload());
        assertEquals(NettyPlayerGatewayHandler.HEARTBEAT_ACK_TOPIC, envelope.topic());
        assertEquals(2, ack.sequence());
        assertEquals("OK", ack.status());
        assertEquals(List.of(), fixture.commands);
        assertEquals(1, fixture.stats().acceptedHeartbeats());
    }

    @Test
    void outboundAckAfterLoginTrimsPendingWindowWithoutBusinessCommand() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);
        fixture.outbound.deliver(new com.commonbattle.game.session.PlayerOutboundEnvelope(
                java.util.Set.of(10001L),
                "system.notice",
                "hello"
        ));
        PlayerClientEnvelope pushed = fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.outboundAck(
                new PlayerClientOutboundAck(10001L, "session-1", 1, pushed.sequence())
        )));

        assertEquals(0, fixture.outbound.pendingAck(10001L).size());
        assertEquals(List.of(), fixture.commands);
        assertEquals(1, fixture.stats().acceptedAcks());
        assertTrue(channel.isOpen());
    }

    @Test
    void staleOutboundAckClosesConnection() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.outboundAck(
                new PlayerClientOutboundAck(10001L, "session-1", 99, 1)
        )));

        PlayerClientRejectResponse reject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(channel).payload());
        assertEquals(PlayerClientErrorCode.SESSION_EXPIRED, reject.code());
        assertEquals(1, fixture.stats().rejectedAcks());
        assertFalse(channel.isOpen());
    }

    @Test
    void slowClientPendingAckLimitClosesOnHeartbeat() {
        Fixture fixture = Fixture.create(PlayerClientAuthenticator.allowAll(), new PlayerGatewayConfig(
                Duration.ZERO,
                true,
                PlayerGatewayDuplicateLoginPolicy.KICK_OLD,
                AgentRateLimitPolicy.perSecond(200, 200),
                AgentRateLimitPolicy.perSecond(60, 60),
                1,
                Duration.ofSeconds(30),
                true
        ));
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);
        fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "hello"));
        fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.heartbeat(
                new PlayerClientHeartbeat(10001L, "session-1", 1, 2)
        )));

        PlayerClientRejectResponse reject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(channel).payload());
        assertEquals(PlayerClientErrorCode.SLOW_CLIENT, reject.code());
        assertEquals(1, fixture.stats().slowClientClosures());
        assertFalse(channel.isOpen());
    }

    @Test
    void outboundAckClearsSlowWindowBeforeHeartbeat() {
        Fixture fixture = Fixture.create(PlayerClientAuthenticator.allowAll(), new PlayerGatewayConfig(
                Duration.ZERO,
                true,
                PlayerGatewayDuplicateLoginPolicy.KICK_OLD,
                AgentRateLimitPolicy.perSecond(200, 200),
                AgentRateLimitPolicy.perSecond(60, 60),
                1,
                Duration.ofSeconds(30),
                true
        ));
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);
        fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "hello"));
        PlayerClientEnvelope pushed = fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.outboundAck(
                new PlayerClientOutboundAck(10001L, "session-1", 1, pushed.sequence())
        )));
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.heartbeat(
                new PlayerClientHeartbeat(10001L, "session-1", 1, 2)
        )));

        PlayerClientHeartbeatAck ack = assertInstanceOf(PlayerClientHeartbeatAck.class,
                fixture.readOutbound(channel).payload());
        assertEquals(2, ack.sequence());
        assertEquals(1, fixture.stats().acceptedAcks());
        assertEquals(0, fixture.stats().slowClientClosures());
        assertTrue(channel.isOpen());
    }

    @Test
    void commandBeforeLoginClosesConnection() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                1,
                1,
                "test.echo",
                "payload"
        ))));

        PlayerClientRejectResponse reject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(channel).payload());
        assertEquals(PlayerClientErrorCode.NOT_LOGGED_IN, reject.code());
        assertEquals(List.of(), fixture.commands);
        assertEquals(1, fixture.stats().rejectedCommands());
        assertFalse(channel.isOpen());
    }

    @Test
    void commandRateLimitClosesConnectionBeforeIngress() {
        Fixture fixture = Fixture.create(PlayerClientAuthenticator.allowAll(), new PlayerGatewayConfig(
                Duration.ZERO,
                true,
                PlayerGatewayDuplicateLoginPolicy.KICK_OLD,
                new AgentRateLimitPolicy(1, 1, Duration.ofSeconds(1)),
                AgentRateLimitPolicy.perSecond(60, 60),
                512,
                Duration.ofSeconds(30),
                true
        ));
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        PlayerClientCommandEnvelope first = new PlayerClientCommandEnvelope(
                10001L, "session-1", 1, 1, "test.echo", "first");
        PlayerClientCommandEnvelope second = new PlayerClientCommandEnvelope(
                10001L, "session-1", 1, 2, "test.echo", "second");
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.command(first)));
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.command(second)));

        PlayerClientRejectResponse reject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(channel).payload());
        assertEquals(PlayerClientErrorCode.COMMAND_RATE_LIMITED, reject.code());
        assertEquals(1000, reject.retryAfterMillis());
        assertEquals(List.of(first), fixture.commands);
        assertEquals(1, fixture.stats().acceptedCommands());
        assertEquals(1, fixture.stats().rateLimitedCommands());
        assertFalse(channel.isOpen());
    }

    @Test
    void heartbeatRateLimitClosesConnection() {
        Fixture fixture = Fixture.create(PlayerClientAuthenticator.allowAll(), new PlayerGatewayConfig(
                Duration.ZERO,
                false,
                PlayerGatewayDuplicateLoginPolicy.KICK_OLD,
                AgentRateLimitPolicy.perSecond(200, 200),
                new AgentRateLimitPolicy(1, 1, Duration.ofSeconds(1)),
                512,
                Duration.ofSeconds(30),
                true
        ));
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.heartbeat(
                new PlayerClientHeartbeat(10001L, "session-1", 1, 1)
        )));
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.heartbeat(
                new PlayerClientHeartbeat(10001L, "session-1", 1, 2)
        )));

        PlayerClientRejectResponse reject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(channel).payload());
        assertEquals(PlayerClientErrorCode.HEARTBEAT_RATE_LIMITED, reject.code());
        assertEquals(1000, reject.retryAfterMillis());
        assertEquals(1, fixture.stats().acceptedHeartbeats());
        assertEquals(1, fixture.stats().rateLimitedHeartbeats());
        assertFalse(channel.isOpen());
    }

    @Test
    void staleSessionCommandWritesRejectCode() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                10001L,
                "session-1",
                99,
                2,
                "test.echo",
                "payload"
        ))));

        PlayerClientRejectResponse reject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(channel).payload());
        assertEquals(PlayerClientErrorCode.SESSION_EXPIRED, reject.code());
        assertEquals(1, fixture.stats().rejectedCommands());
        assertFalse(channel.isOpen());
    }

    @Test
    void duplicateLoginRejectNewKeepsOldConnection() {
        Fixture fixture = Fixture.create(PlayerClientAuthenticator.allowAll(), new PlayerGatewayConfig(
                Duration.ZERO,
                true,
                PlayerGatewayDuplicateLoginPolicy.REJECT_NEW,
                AgentRateLimitPolicy.perSecond(200, 200),
                AgentRateLimitPolicy.perSecond(60, 60),
                512,
                Duration.ofSeconds(30),
                true
        ));
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel second = fixture.channel();
        first.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(first);

        second.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-2")
        )));

        PlayerClientLoginResponse response = assertInstanceOf(PlayerClientLoginResponse.class,
                fixture.readOutbound(second).payload());
        assertEquals("FAILED", response.status());
        assertEquals(PlayerClientErrorCode.DUPLICATE_LOGIN, response.code());
        assertEquals("duplicate login rejected", response.message());
        assertTrue(first.isOpen());
        assertFalse(second.isOpen());
        assertEquals(1, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(1, fixture.stats().duplicateRejectedLogins());
    }

    @Test
    void duplicateLoginKickOldClosesOldConnection() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel first = fixture.channel();
        EmbeddedChannel second = fixture.channel();
        first.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(first);

        second.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-2")
        )));

        PlayerClientLoginResponse response = assertInstanceOf(PlayerClientLoginResponse.class,
                fixture.readOutbound(second).payload());
        assertEquals("OK", response.status());
        assertEquals(2, response.sessionEpoch());
        PlayerClientRejectResponse oldReject = assertInstanceOf(PlayerClientRejectResponse.class,
                fixture.readOutbound(first).payload());
        assertEquals(PlayerClientErrorCode.DUPLICATE_LOGIN, oldReject.code());
        assertFalse(first.isOpen());
        assertTrue(second.isOpen());
        assertEquals(1, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(1, fixture.stats().kickedConnections());
    }

    @Test
    void rejectedAuthenticatorWritesFailedLoginResponseAndCloses() {
        Fixture fixture = Fixture.create((request, remoteAddress) ->
                PlayerClientAuthResult.rejected("bad token"), PlayerGatewayConfig.defaults());
        EmbeddedChannel channel = fixture.channel();

        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1", "bad")
        )));

        PlayerClientEnvelope envelope = fixture.readOutbound(channel);
        PlayerClientLoginResponse response = assertInstanceOf(PlayerClientLoginResponse.class, envelope.payload());
        assertEquals("FAILED", response.status());
        assertEquals(PlayerClientErrorCode.AUTH_REJECTED, response.code());
        assertEquals("bad token", response.message());
        assertEquals(0, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(1, fixture.stats().failedLogins());
        assertEquals(1, fixture.stats().authRejectedLogins());
        assertFalse(channel.isOpen());
    }

    @Test
    void readerIdleEventClosesConnectionAndUnbindsSession() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);

        assertFalse(channel.isOpen());
        assertEquals(0, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(1, fixture.stats().idleTimeouts());
        assertEquals(1, fixture.stats().disconnectedSessions());
    }

    @Test
    void channelInactiveUnbindsCurrentSession() {
        Fixture fixture = Fixture.create();
        EmbeddedChannel channel = fixture.channel();
        channel.writeInbound(frame(fixture.inboundCodec, PlayerClientInboundEnvelope.login(
                new PlayerClientLoginRequest(10001L, "session-1")
        )));
        fixture.readOutbound(channel);

        channel.close();

        assertEquals(0, fixture.outbound.deliveryStats().activeConnections());
        assertEquals(java.util.Optional.empty(), fixture.sessions.current(10001L));
        assertEquals(1, fixture.stats().disconnectedSessions());
    }

    private static ByteBuf frame(ProtoPlayerClientInboundCodec codec, PlayerClientInboundEnvelope envelope) {
        byte[] body = codec.encode(envelope);
        return Unpooled.buffer(Integer.BYTES + body.length)
                .writeInt(body.length)
                .writeBytes(body);
    }

    private record Fixture(
            InMemoryPlayerSessionRegistry sessions,
            PlayerOutboundDeliveryHub outbound,
            PlayerClientConnectionService connections,
            ProtoPlayerClientInboundCodec inboundCodec,
            ProtoPlayerClientCodec outboundCodec,
            NettyPlayerGatewayMetrics metrics,
            NettyPlayerGatewayConnectionIndex connectionIndex,
            PlayerClientAuthenticator authenticator,
            PlayerGatewayConfig config,
            List<PlayerClientCommandEnvelope> commands
    ) {
        private static Fixture create() {
            return create(PlayerClientAuthenticator.allowAll(), PlayerGatewayConfig.defaults());
        }

        private static Fixture create(PlayerClientAuthenticator authenticator, PlayerGatewayConfig config) {
            RecordingExecutor executor = new RecordingExecutor();
            ActorSystem actors = new ActorSystem(executor, 64);
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK
            );
            InMemoryGameConfigRegistry configs = new InMemoryGameConfigRegistry(new GameConfigValidator(), CLOCK);
            assertEquals(GameConfigPublishStatus.PUBLISHED,
                    configs.publish(ExampleGameConfigs.basic(7, CLOCK.instant())).status());
            PlayerGameAgentManager agents = new PlayerGameAgentManager(
                    actors,
                    messages,
                    new InMemoryPlayerStateRepository(),
                    configs,
                    lifecycles,
                    CLOCK,
                    SERVER_OPEN_TIME
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                    sessions,
                    CLOCK,
                    8,
                    PlayerDeliveryOverflowStrategy.DROP_OLDEST
            );
            PlayerClientConnectionService connections = new PlayerClientConnectionService(
                    new PlayerLoginService(agents, sessions),
                    outbound
            );
            List<PlayerClientCommandEnvelope> commands = new ArrayList<>();
            PayloadCodecRegistry registry = PlayerClientPayloadCodecs.registerTo(
                    PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults())
            );
            NettyPlayerGatewayMetrics metrics = new NettyPlayerGatewayMetrics();
            return new Fixture(
                    sessions,
                    outbound,
                    connections,
                    new ProtoPlayerClientInboundCodec(registry),
                    new ProtoPlayerClientCodec(registry),
                    metrics,
                    new NettyPlayerGatewayConnectionIndex(),
                    authenticator,
                    config,
                    commands
            );
        }

        private EmbeddedChannel channel() {
            NettyPlayerGatewayHandler handler = new NettyPlayerGatewayHandler(
                    connections,
                    commands::add,
                    CLOCK,
                    metrics,
                    authenticator,
                    config,
                    connectionIndex
            );
            return new EmbeddedChannel(
                    new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4),
                    new NettyPlayerClientInboundFrameDecoder(inboundCodec),
                    new LengthFieldPrepender(4),
                    new NettyPlayerClientFrameEncoder(outboundCodec),
                    handler
            );
        }

        private PlayerClientEnvelope readOutbound(EmbeddedChannel channel) {
            ByteBuf lengthHeader = channel.readOutbound();
            ByteBuf body = channel.readOutbound();
            int frameLength = lengthHeader.readInt();
            byte[] bytes = new byte[frameLength];
            body.readBytes(bytes);
            return outboundCodec.decode(bytes);
        }

        private NettyPlayerGatewayStats stats() {
            return metrics.snapshot();
        }

    }

    private static final class RecordingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class NoopRpcGateway implements RpcGateway {
        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
        }
    }
}
