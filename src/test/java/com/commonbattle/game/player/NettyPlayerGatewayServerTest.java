package com.commonbattle.game.player;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.NettyPlayerClientFrameDecoder;
import com.commonbattle.game.session.NettyPlayerClientInboundFrameEncoder;
import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientConnectionService;
import com.commonbattle.game.session.PlayerClientEnvelope;
import com.commonbattle.game.session.PlayerClientInboundEnvelope;
import com.commonbattle.game.session.PlayerClientLoginRequest;
import com.commonbattle.game.session.PlayerClientLoginResponse;
import com.commonbattle.game.session.PlayerClientPayloadCodecs;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerLoginService;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.ProtoPlayerClientCodec;
import com.commonbattle.game.session.ProtoPlayerClientInboundCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyPlayerGatewayServerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant SERVER_OPEN_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void acceptsLoginAndForwardsCommandOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        CountDownLatch loginReceived = new CountDownLatch(1);
        AtomicReference<PlayerClientEnvelope> loginEnvelope = new AtomicReference<>();

        try (NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                new ServiceEndpoint("127.0.0.1", port),
                fixture.connections,
                fixture.commandAcceptor,
                fixture.codecs,
                CLOCK
        )) {
            server.start();
            NioEventLoopGroup clientGroup = new NioEventLoopGroup(1);
            Channel client = null;
            try {
                client = new Bootstrap()
                        .group(clientGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel channel) {
                                channel.pipeline()
                                        .addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4))
                                        .addLast(new NettyPlayerClientFrameDecoder(new ProtoPlayerClientCodec(fixture.codecs)))
                                        .addLast(new LengthFieldPrepender(4))
                                        .addLast(new NettyPlayerClientInboundFrameEncoder(new ProtoPlayerClientInboundCodec(fixture.codecs)))
                                        .addLast(new SimpleChannelInboundHandler<PlayerClientEnvelope>() {
                                            @Override
                                            protected void channelRead0(ChannelHandlerContext context, PlayerClientEnvelope envelope) {
                                                loginEnvelope.set(envelope);
                                                loginReceived.countDown();
                                            }
                                        });
                            }
                        })
                        .connect("127.0.0.1", port)
                        .sync()
                        .channel();

                client.writeAndFlush(PlayerClientInboundEnvelope.login(
                        new PlayerClientLoginRequest(10001L, "session-1")
                )).sync();

                assertTrue(loginReceived.await(3, TimeUnit.SECONDS));
                PlayerClientLoginResponse response = assertInstanceOf(
                        PlayerClientLoginResponse.class,
                        loginEnvelope.get().payload()
                );
                assertEquals(10001L, response.playerId());
                assertEquals("session-1", response.sessionId());
                assertEquals(1, response.sessionEpoch());
                assertEquals("OK", response.status());

                PlayerClientCommandEnvelope command = new PlayerClientCommandEnvelope(
                        10001L,
                        "session-1",
                        1,
                        1,
                        "test.echo",
                        "payload"
                );
                client.writeAndFlush(PlayerClientInboundEnvelope.command(command)).sync();

                assertTrue(fixture.commandReceived.await(3, TimeUnit.SECONDS));
                assertEquals(List.of(command), List.copyOf(fixture.commands));
                assertEventually(() -> server.stats().acceptedLogins() == 1
                        && server.stats().acceptedCommands() == 1);

                client.close().sync();
                assertEventually(() -> fixture.outbound.deliveryStats().activeConnections() == 0
                        && server.stats().disconnectedSessions() == 1);
            } finally {
                if (client != null && client.isOpen()) {
                    client.close().awaitUninterruptibly(1, TimeUnit.SECONDS);
                }
                clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS)
                        .awaitUninterruptibly(1, TimeUnit.SECONDS);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void assertEventually(BooleanSupplier assertion) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (assertion.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(assertion.getAsBoolean());
    }

    private record Fixture(
            PlayerClientConnectionService connections,
            PlayerOutboundDeliveryHub outbound,
            PayloadCodecRegistry codecs,
            PlayerClientCommandAcceptor commandAcceptor,
            List<PlayerClientCommandEnvelope> commands,
            CountDownLatch commandReceived
    ) {
        private static Fixture create() {
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
            PayloadCodecRegistry codecs = PlayerClientPayloadCodecs.registerTo(
                    PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults())
            );
            List<PlayerClientCommandEnvelope> commands = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch commandReceived = new CountDownLatch(1);
            PlayerClientCommandAcceptor commandAcceptor = command -> {
                commands.add(command);
                commandReceived.countDown();
            };
            return new Fixture(connections, outbound, codecs, commandAcceptor, commands, commandReceived);
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

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
