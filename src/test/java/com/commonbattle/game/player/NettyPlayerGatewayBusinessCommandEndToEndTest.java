package com.commonbattle.game.player;

import com.commonbattle.actor.ActorSystem;
import com.commonbattle.actor.agent.InMemoryAgentDirectory;
import com.commonbattle.actor.agent.lifecycle.AgentLifecycleManager;
import com.commonbattle.actor.agent.lifecycle.LifecycleAwareAgentRouter;
import com.commonbattle.actor.backpressure.AgentRateLimitPolicy;
import com.commonbattle.actor.backpressure.AdmissionControlledAgentRouter;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.message.DefaultAgentMessagePort;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.ServiceKind;
import com.commonbattle.cluster.boot.BootPayloadCodecs;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.example.config.ExampleGameConfigs;
import com.commonbattle.game.config.GameConfigPublishStatus;
import com.commonbattle.game.config.GameConfigValidator;
import com.commonbattle.game.config.InMemoryGameConfigRegistry;
import com.commonbattle.game.session.InMemoryPlayerSessionRegistry;
import com.commonbattle.game.session.NettyPlayerClientFrameDecoder;
import com.commonbattle.game.session.NettyPlayerClientInboundFrameEncoder;
import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import com.commonbattle.game.session.PlayerClientCommandResponse;
import com.commonbattle.game.session.PlayerClientConnectionService;
import com.commonbattle.game.session.PlayerClientEnvelope;
import com.commonbattle.game.session.PlayerClientErrorCode;
import com.commonbattle.game.session.PlayerClientHeartbeat;
import com.commonbattle.game.session.PlayerClientInboundEnvelope;
import com.commonbattle.game.session.PlayerClientLoginRequest;
import com.commonbattle.game.session.PlayerClientLoginResponse;
import com.commonbattle.game.session.PlayerClientOutboundAck;
import com.commonbattle.game.session.PlayerClientPayloadCodecs;
import com.commonbattle.game.session.PlayerClientRejectResponse;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandSequencer;
import com.commonbattle.game.session.PlayerDeliveryOverflowStrategy;
import com.commonbattle.game.session.PlayerLoginService;
import com.commonbattle.game.session.PlayerOutboundDeliveryHub;
import com.commonbattle.game.session.PlayerOutboundEnvelope;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyPlayerGatewayBusinessCommandEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void clientCommandRunsInPlayerMailboxAndRespondsOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            try (TestClient client = TestClient.connect(port, fixture.codecs)) {

                client.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                PlayerClientLoginResponse login = assertPayload(client.nextEnvelope(), PlayerClientLoginResponse.class);
                assertEquals("OK", login.status());
                assertEquals(1, login.sessionEpoch());

                client.send(PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                        10001L,
                        "session-1",
                        1,
                        1,
                        "test.echo",
                        "hello"
                )));

                PlayerClientCommandResponse response = assertPayload(client.nextEnvelope(), PlayerClientCommandResponse.class);
                assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status());
                assertEquals(PlayerBusinessResponse.OK, response.code());
                assertEquals(PlayerBusinessAck.OK, response.payload());
                assertFalse(response.replayed());
                assertEquals(List.of("test-player-actor"), fixture.businessThreads().get());
                assertEquals(1, fixture.handled.get());
                assertEventually(() -> server.stats().acceptedCommands() == 1);
            }
        }
    }

    @Test
    void realBusinessCommandPushesFreshSnapshotAndResponseOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.createBusinessPush();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            try (TestClient client = TestClient.connect(port, fixture.codecs)) {
                client.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(client.nextEnvelope(), PlayerClientLoginResponse.class);

                client.send(PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                        10001L,
                        "session-1",
                        1,
                        1,
                        PlayerBusinessOperations.ACTIVITY_PROGRESS,
                        new ActivityProgressCommand("battle-win-1", 1)
                )));

                List<PlayerClientEnvelope> frames = List.of(client.nextEnvelope(), client.nextEnvelope());
                PlayerClientCommandResponse response = frames.stream()
                        .map(PlayerClientEnvelope::payload)
                        .filter(PlayerClientCommandResponse.class::isInstance)
                        .map(PlayerClientCommandResponse.class::cast)
                        .findFirst()
                        .orElseThrow();
                PlayerPushPayloads.ActivityProgressPayload progress = frames.stream()
                        .filter(envelope -> PlayerOutboundTopicPolicies.ACTIVITY_PROGRESS.equals(envelope.topic()))
                        .map(PlayerClientEnvelope::payload)
                        .filter(PlayerPushPayloads.ActivityProgressPayload.class::isInstance)
                        .map(PlayerPushPayloads.ActivityProgressPayload.class::cast)
                        .findFirst()
                        .orElseThrow();

                assertEquals(PlayerBusinessResponseStatus.SUCCESS, response.status());
                assertEquals(PlayerBusinessAck.OK, response.payload());
                assertEquals(1, progress.progress.get("battle-win-1").value);
                assertEquals(false, progress.progress.get("battle-win-1").claimed);
                assertEquals(2, fixture.outbound.pendingAck(10001L).size());
            }
        }
    }

    @Test
    void duplicateClientCommandReplaysCompletedResponseOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            try (TestClient client = TestClient.connect(port, fixture.codecs)) {
                client.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(client.nextEnvelope(), PlayerClientLoginResponse.class);
                PlayerClientInboundEnvelope command = PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                        10001L,
                        "session-1",
                        1,
                        1,
                        "test.echo",
                        "hello"
                ));

                client.send(command);
                PlayerClientCommandResponse first = assertPayload(client.nextEnvelope(), PlayerClientCommandResponse.class);
                client.send(command);
                PlayerClientCommandResponse second = assertPayload(client.nextEnvelope(), PlayerClientCommandResponse.class);

                assertEquals(PlayerBusinessResponseStatus.SUCCESS, first.status());
                assertFalse(first.replayed());
                assertEquals(PlayerBusinessResponseStatus.SUCCESS, second.status());
                assertTrue(second.replayed());
                assertEquals(PlayerBusinessAck.OK, second.payload());
                assertEquals(1, fixture.handled.get());
                assertEquals(1, fixture.businessThreads().get().size());
            }
        }
    }

    @Test
    void businessFailureInPlayerMailboxRespondsOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            try (TestClient client = TestClient.connect(port, fixture.codecs)) {
                client.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(client.nextEnvelope(), PlayerClientLoginResponse.class);

                client.send(PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                        10001L,
                        "session-1",
                        1,
                        1,
                        "test.fail",
                        "bad"
                )));

                PlayerClientCommandResponse response = assertPayload(client.nextEnvelope(), PlayerClientCommandResponse.class);
                assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
                assertEquals(PlayerBusinessResponse.BAD_REQUEST, response.code());
                assertEquals("bad business", response.message());
                assertFalse(response.replayed());
                assertEquals(List.of("test-player-actor"), fixture.businessThreads().get());
            }
        }
    }

    @Test
    void mailboxPressureRespondsWithRetryAfterOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create((target, operation) ->
                AdmissionDecision.reject("mailbox_pressure:target", Duration.ofMillis(50)));
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            try (TestClient client = TestClient.connect(port, fixture.codecs)) {
                client.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(client.nextEnvelope(), PlayerClientLoginResponse.class);

                client.send(PlayerClientInboundEnvelope.command(new PlayerClientCommandEnvelope(
                        10001L,
                        "session-1",
                        1,
                        1,
                        "test.echo",
                        "hello"
                )));

                PlayerClientCommandResponse response = assertPayload(client.nextEnvelope(), PlayerClientCommandResponse.class);
                assertEquals(PlayerBusinessResponseStatus.FAILED, response.status());
                assertEquals("BACKPRESSURED", response.code());
                assertEquals("mailbox_pressure:target", response.message());
                assertEquals(50, response.retryAfterMillis());
                assertFalse(response.replayed());
                assertEquals(0, fixture.handled.get());
            }
        }
    }

    @Test
    void reconnectReplaysUnackedAndOfflinePushesOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            long firstPushSequence;
            try (TestClient first = TestClient.connect(port, fixture.codecs)) {
                first.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(first.nextEnvelope(), PlayerClientLoginResponse.class);

                fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "unacked-online"));
                PlayerClientEnvelope pushed = first.nextEnvelope();
                assertEquals("system.notice", pushed.topic());
                assertEquals("unacked-online", pushed.payload());
                firstPushSequence = pushed.sequence();
            }
            assertEventually(() -> fixture.outbound.deliveryStats().activeConnections() == 0);

            fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "offline-after-drop"));
            try (TestClient second = TestClient.connect(port, fixture.codecs)) {
                second.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-2")));
                List<PlayerClientEnvelope> reconnectFrames = List.of(
                        second.nextEnvelope(),
                        second.nextEnvelope(),
                        second.nextEnvelope()
                );
                PlayerClientLoginResponse login = reconnectFrames.stream()
                        .map(PlayerClientEnvelope::payload)
                        .filter(PlayerClientLoginResponse.class::isInstance)
                        .map(PlayerClientLoginResponse.class::cast)
                        .findFirst()
                        .orElseThrow();
                assertEquals(2, login.sessionEpoch());
                List<Object> pushedPayloads = reconnectFrames.stream()
                        .filter(envelope -> "system.notice".equals(envelope.topic()))
                        .map(PlayerClientEnvelope::payload)
                        .toList();
                assertEquals(List.of("unacked-online", "offline-after-drop"), pushedPayloads);
                long maxPushSequence = reconnectFrames.stream()
                        .filter(envelope -> "system.notice".equals(envelope.topic()))
                        .mapToLong(PlayerClientEnvelope::sequence)
                        .max()
                        .orElse(firstPushSequence);

                second.send(PlayerClientInboundEnvelope.outboundAck(
                        new PlayerClientOutboundAck(10001L, "session-2", 2, maxPushSequence)
                ));
                assertEventually(() -> fixture.outbound.pendingAck(10001L).isEmpty());
                assertEventually(() -> server.stats().acceptedAcks() == 1);
            }
        }
    }

    @Test
    void reconnectReplaysOnlyLatestCoalescedPushOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK
             )) {
            server.start();
            try (TestClient first = TestClient.connect(port, fixture.codecs)) {
                first.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(first.nextEnvelope(), PlayerClientLoginResponse.class);
                fixture.outbound.deliver(PlayerOutboundEnvelope.coalescing(
                        Set.of(10001L),
                        "scene.snapshot",
                        "pos-1",
                        "scene:1"
                ));
                fixture.outbound.deliver(PlayerOutboundEnvelope.coalescing(
                        Set.of(10001L),
                        "scene.snapshot",
                        "pos-2",
                        "scene:1"
                ));
                assertEquals("pos-1", first.nextEnvelope().payload());
                assertEquals("pos-2", first.nextEnvelope().payload());
            }
            assertEventually(() -> fixture.outbound.deliveryStats().activeConnections() == 0);

            try (TestClient second = TestClient.connect(port, fixture.codecs)) {
                second.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-2")));
                List<PlayerClientEnvelope> reconnectFrames = List.of(
                        second.nextEnvelope(),
                        second.nextEnvelope()
                );
                List<Object> pushedPayloads = reconnectFrames.stream()
                        .filter(envelope -> "scene.snapshot".equals(envelope.topic()))
                        .map(PlayerClientEnvelope::payload)
                        .toList();
                assertEquals(List.of("pos-2"), pushedPayloads);
                assertEquals(1, fixture.outbound.deliveryStats().coalescedDeliveries());
            }
        }
    }

    @Test
    void slowClientPendingAckLimitRejectsHeartbeatOverTcp() throws Exception {
        int port = freePort();
        Fixture fixture = Fixture.create();
        try (fixture;
             NettyPlayerGatewayServer server = new NettyPlayerGatewayServer(
                     new ServiceEndpoint("127.0.0.1", port),
                     fixture.connections,
                     fixture.ingress,
                     fixture.codecs,
                     CLOCK,
                     PlayerClientAuthenticator.allowAll(),
                     new PlayerGatewayConfig(
                             Duration.ZERO,
                             true,
                             PlayerGatewayDuplicateLoginPolicy.KICK_OLD,
                             AgentRateLimitPolicy.perSecond(200, 200),
                             AgentRateLimitPolicy.perSecond(60, 60),
                             1,
                             Duration.ofSeconds(30),
                             true
                     )
             )) {
            server.start();
            try (TestClient client = TestClient.connect(port, fixture.codecs)) {
                client.send(PlayerClientInboundEnvelope.login(new PlayerClientLoginRequest(10001L, "session-1")));
                assertPayload(client.nextEnvelope(), PlayerClientLoginResponse.class);
                fixture.outbound.deliver(new PlayerOutboundEnvelope(Set.of(10001L), "system.notice", "unacked-online"));
                assertEquals("unacked-online", client.nextEnvelope().payload());

                client.send(PlayerClientInboundEnvelope.heartbeat(new PlayerClientHeartbeat(10001L, "session-1", 1, 2)));

                PlayerClientRejectResponse reject = assertPayload(client.nextEnvelope(), PlayerClientRejectResponse.class);
                assertEquals(PlayerClientErrorCode.SLOW_CLIENT, reject.code());
                assertEventually(() -> server.stats().slowClientClosures() == 1);
            }
        }
    }

    private static <T> T assertPayload(PlayerClientEnvelope envelope, Class<T> type) {
        assertNotNull(envelope);
        return assertInstanceOf(type, envelope.payload());
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
            ExecutorService actorExecutor,
            PlayerClientConnectionService connections,
            PlayerClientCommandIngress ingress,
            PlayerOutboundDeliveryHub outbound,
            PayloadCodecRegistry codecs,
            AtomicInteger handled,
            AtomicReference<List<String>> businessThreads
    ) implements AutoCloseable {
        private static Fixture create() {
            return create((target, operation) -> AdmissionDecision.accept());
        }

        private static Fixture create(com.commonbattle.actor.backpressure.InboundAdmissionController admissions) {
            ExecutorService actorExecutor = Executors.newSingleThreadExecutor(runnable ->
                    new Thread(runnable, "test-player-actor"));
            ActorSystem actors = new ActorSystem(actorExecutor, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                    sessions,
                    CLOCK,
                    8,
                    PlayerDeliveryOverflowStrategy.DROP_OLDEST
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
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
                    Instant.parse("2026-08-01T00:00:00Z")
            );
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            admissions,
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    )
            );
            AtomicInteger handled = new AtomicInteger();
            AtomicReference<List<String>> businessThreads = new AtomicReference<>(List.of());
            dispatcher.handle("test.echo", (context, command) -> {
                handled.incrementAndGet();
                businessThreads.set(List.of(Thread.currentThread().getName()));
                responses.succeeded(command, PlayerBusinessAck.OK);
            });
            dispatcher.handle("test.fail", (context, command) -> {
                handled.incrementAndGet();
                businessThreads.set(List.of(Thread.currentThread().getName()));
                responses.failed(command, new IllegalArgumentException("bad business"));
            });
            PlayerBusinessCommandGateway commandGateway = new PlayerBusinessCommandGateway(
                    dispatcher,
                    new NoopRpcGateway(),
                    responses,
                    Duration.ofSeconds(1),
                    new DirectScheduler()
            );
            PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(commandGateway, outbound);
            PayloadCodecRegistry codecs = PlayerClientPayloadCodecs.registerTo(
                    PlayerBusinessCommandPayloadCodecs.registerTo(PayloadCodecRegistry.commonDefaults())
            );
            PlayerClientConnectionService connections = new PlayerClientConnectionService(
                    new PlayerLoginService(agents, sessions),
                    outbound
            );
            return new Fixture(actorExecutor, connections, ingress, outbound, codecs, handled, businessThreads);
        }

        private static Fixture createBusinessPush() {
            ExecutorService actorExecutor = Executors.newSingleThreadExecutor(runnable ->
                    new Thread(runnable, "test-player-actor"));
            ActorSystem actors = new ActorSystem(actorExecutor, 64);
            InMemoryAgentDirectory directory = new InMemoryAgentDirectory();
            AgentLifecycleManager lifecycles = new AgentLifecycleManager(
                    ServiceId.of(ServiceKind.GAME, "r1", "game-1"),
                    actors,
                    directory,
                    CLOCK
            );
            InMemoryPlayerSessionRegistry sessions = new InMemoryPlayerSessionRegistry(CLOCK);
            PlayerOutboundDeliveryHub outbound = new PlayerOutboundDeliveryHub(
                    sessions,
                    CLOCK,
                    8,
                    PlayerDeliveryOverflowStrategy.DROP_OLDEST
            );
            DefaultAgentMessagePort messages = new DefaultAgentMessagePort(actors, new NoopRpcGateway());
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
                    Instant.parse("2026-08-01T00:00:00Z"),
                    ignored -> {
                    },
                    null,
                    PlayerStateSaveListener.ignore(),
                    null,
                    new OutboundPlayerPushPort(outbound)
            );
            PlayerCommandDispatcher dispatcher = new PlayerCommandDispatcher(
                    sessions,
                    new PlayerCommandSequencer(),
                    new AdmissionControlledAgentRouter(
                            (target, operation) -> AdmissionDecision.accept(),
                            new LifecycleAwareAgentRouter(lifecycles, messages)
                    )
            );
            PlayerBusinessResponseHub responses = new PlayerBusinessResponseHub();
            PlayerBusinessCommandBinder.registerExamples(
                    dispatcher,
                    new PlayerBusinessCommandHandler(agents::getOrCreate, responses)
            );
            PlayerBusinessCommandGateway commandGateway = new PlayerBusinessCommandGateway(
                    dispatcher,
                    new NoopRpcGateway(),
                    responses,
                    Duration.ofSeconds(1),
                    new DirectScheduler()
            );
            PlayerClientCommandIngress ingress = new PlayerClientCommandIngress(commandGateway, outbound);
            PayloadCodecRegistry codecs = BootPayloadCodecs.gameServer();
            PlayerClientConnectionService connections = new PlayerClientConnectionService(
                    new PlayerLoginService(agents, sessions),
                    outbound
            );
            return new Fixture(actorExecutor, connections, ingress, outbound, codecs,
                    new AtomicInteger(), new AtomicReference<>(List.of()));
        }

        @Override
        public void close() {
            actorExecutor.shutdownNow();
        }
    }

    private static final class TestClient implements AutoCloseable {
        private final NioEventLoopGroup group;
        private final Channel channel;
        private final BlockingQueue<PlayerClientEnvelope> inbound;

        private TestClient(NioEventLoopGroup group, Channel channel, BlockingQueue<PlayerClientEnvelope> inbound) {
            this.group = group;
            this.channel = channel;
            this.inbound = inbound;
        }

        private static TestClient connect(int port, PayloadCodecRegistry codecs) throws InterruptedException {
            BlockingQueue<PlayerClientEnvelope> inbound = new LinkedBlockingQueue<>();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            Channel channel = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4))
                                    .addLast(new NettyPlayerClientFrameDecoder(new ProtoPlayerClientCodec(codecs)))
                                    .addLast(new LengthFieldPrepender(4))
                                    .addLast(new NettyPlayerClientInboundFrameEncoder(new ProtoPlayerClientInboundCodec(codecs)))
                                    .addLast(new SimpleChannelInboundHandler<PlayerClientEnvelope>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext context, PlayerClientEnvelope envelope) {
                                            inbound.add(envelope);
                                        }
                                    });
                        }
                    })
                    .connect("127.0.0.1", port)
                    .sync()
                    .channel();
            return new TestClient(group, channel, inbound);
        }

        private void send(PlayerClientInboundEnvelope envelope) throws InterruptedException {
            channel.writeAndFlush(envelope).sync();
        }

        private PlayerClientEnvelope nextEnvelope() throws InterruptedException {
            return inbound.poll(3, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            if (channel.isOpen()) {
                channel.close().awaitUninterruptibly(1, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS)
                    .awaitUninterruptibly(1, TimeUnit.SECONDS);
        }
    }

    private static final class DirectScheduler extends ScheduledThreadPoolExecutor {
        private DirectScheduler() {
            super(1, runnable -> {
                Thread thread = new Thread(runnable, "test-player-client-command-timeout");
                thread.setDaemon(true);
                return thread;
            });
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
