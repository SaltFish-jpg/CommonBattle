package com.commonbattle.game.player;

import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.session.NettyPlayerClientFrameEncoder;
import com.commonbattle.game.session.NettyPlayerClientInboundFrameDecoder;
import com.commonbattle.game.session.PlayerClientConnectionService;
import com.commonbattle.game.session.ProtoPlayerClientCodec;
import com.commonbattle.game.session.ProtoPlayerClientInboundCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家客户端 Netty 网关服务。
 * 它只承载连接、登录绑定、入站投递和出站写回，不直接执行业务逻辑。
 */
public final class NettyPlayerGatewayServer implements AutoCloseable, PlayerGatewayView {
    private static final int MAX_FRAME_SIZE = 1024 * 1024;
    private static final long CLOSE_TIMEOUT_MILLIS = 1_000;

    private final ServiceEndpoint endpoint;
    private final PlayerClientConnectionService connections;
    private final PlayerClientCommandAcceptor commands;
    private final ProtoPlayerClientInboundCodec inboundCodec;
    private final ProtoPlayerClientCodec outboundCodec;
    private final Clock clock;
    private final PlayerClientAuthenticator authenticator;
    private final PlayerGatewayConfig config;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private final NettyPlayerGatewayMetrics metrics = new NettyPlayerGatewayMetrics();
    private final NettyPlayerGatewayConnectionIndex connectionIndex = new NettyPlayerGatewayConnectionIndex();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Channel serverChannel;

    public NettyPlayerGatewayServer(
            ServiceEndpoint endpoint,
            PlayerClientConnectionService connections,
            PlayerClientCommandAcceptor commands,
            PayloadCodecRegistry payloadCodecs,
            Clock clock
    ) {
        this(endpoint, connections, commands, payloadCodecs, clock,
                PlayerClientAuthenticator.allowAll(), PlayerGatewayConfig.defaults());
    }

    public NettyPlayerGatewayServer(
            ServiceEndpoint endpoint,
            PlayerClientConnectionService connections,
            PlayerClientCommandAcceptor commands,
            PayloadCodecRegistry payloadCodecs,
            Clock clock,
            PlayerClientAuthenticator authenticator,
            PlayerGatewayConfig config
    ) {
        this(endpoint, connections, commands, payloadCodecs, clock, authenticator, config,
                new NioEventLoopGroup(1), new NioEventLoopGroup());
    }

    NettyPlayerGatewayServer(
            ServiceEndpoint endpoint,
            PlayerClientConnectionService connections,
            PlayerClientCommandAcceptor commands,
            PayloadCodecRegistry payloadCodecs,
            Clock clock,
            PlayerClientAuthenticator authenticator,
            PlayerGatewayConfig config,
            NioEventLoopGroup bossGroup,
            NioEventLoopGroup workerGroup
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.commands = Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(payloadCodecs, "payloadCodecs");
        this.inboundCodec = new ProtoPlayerClientInboundCodec(payloadCodecs);
        this.outboundCodec = new ProtoPlayerClientCodec(payloadCodecs);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.config = Objects.requireNonNull(config, "config");
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
    }

    public NettyPlayerGatewayServer start() {
        ensureOpen();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            NettyPlayerGatewayHandler gatewayHandler =
                                    new NettyPlayerGatewayHandler(
                                            connections,
                                            commands,
                                            clock,
                                            metrics,
                                            authenticator,
                                            config,
                                            connectionIndex
                                    );
                            if (!config.readerIdleTimeout().isZero()) {
                                channel.pipeline().addLast(new IdleStateHandler(
                                        config.readerIdleTimeout().toMillis(),
                                        0,
                                        0,
                                        TimeUnit.MILLISECONDS
                                ));
                            }
                            channel.pipeline()
                                    .addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_SIZE, 0, 4, 0, 4))
                                    .addLast(new NettyPlayerClientInboundFrameDecoder(inboundCodec))
                                    .addLast(new LengthFieldPrepender(4))
                                    .addLast(new NettyPlayerClientFrameEncoder(outboundCodec))
                                    .addLast(gatewayHandler);
                        }
                    });
            serverChannel = bootstrap.bind(endpoint.host(), endpoint.port()).sync().channel();
            return this;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding player gateway", e);
        }
    }

    @Override
    public NettyPlayerGatewayStats gatewayStats() {
        return stats();
    }

    public NettyPlayerGatewayStats stats() {
        return metrics.snapshot();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
        }
        bossGroup.shutdownGracefully(0, CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
        workerGroup.shutdownGracefully(0, CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("player gateway is closed");
        }
    }
}
