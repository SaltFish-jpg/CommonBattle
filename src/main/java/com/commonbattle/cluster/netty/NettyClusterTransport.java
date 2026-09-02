package com.commonbattle.cluster.netty;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterMessageHandler;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Netty 的跨服传输实现。
 * 该层只负责连接、编解码和投递 ClusterEnvelope，业务恢复仍由 Actor/RPC 层决定。
 */
public final class NettyClusterTransport implements ClusterTransport {
    private static final int MAX_FRAME_SIZE = 1024 * 1024;

    private final ServiceRegistryView endpoints;
    private final ProtoClusterCodec codec;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private final Map<ServiceId, Channel> channels = new ConcurrentHashMap<>();
    private final AtomicLong connectionAttempts = new AtomicLong();
    private final AtomicLong connectionFailures = new AtomicLong();
    private final AtomicLong sentEnvelopes = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();
    private final AtomicLong receivedEnvelopes = new AtomicLong();
    private final AtomicLong inboundFailures = new AtomicLong();
    private volatile Channel serverChannel;

    public NettyClusterTransport(ServiceRegistryView endpoints) {
        this(endpoints, PayloadCodecRegistry.commonDefaults());
    }

    public NettyClusterTransport(ServiceRegistryView endpoints, PayloadCodecRegistry payloadCodecs) {
        this(endpoints, payloadCodecs, new NioEventLoopGroup(1), new NioEventLoopGroup());
    }

    NettyClusterTransport(
            ServiceRegistryView endpoints,
            PayloadCodecRegistry payloadCodecs,
            NioEventLoopGroup bossGroup,
            NioEventLoopGroup workerGroup
    ) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.codec = new ProtoClusterCodec(Objects.requireNonNull(payloadCodecs, "payloadCodecs"));
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
    }

    @Override
    public void bind(ServiceDescriptor local, ClusterMessageHandler handler) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(handler, "handler");
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new EnvelopeChannelInitializer(codec, handler, receivedEnvelopes, inboundFailures));
            serverChannel = bootstrap.bind(local.endpoint().host(), local.endpoint().port()).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding Netty transport", e);
        }
    }

    @Override
    public void send(ServiceId nextHop, ClusterEnvelope envelope) {
        Objects.requireNonNull(nextHop, "nextHop");
        Objects.requireNonNull(envelope, "envelope");
        Channel channel = channels.compute(nextHop, (serviceId, existing) ->
                existing != null && existing.isActive() ? existing : connect(serviceId));
        try {
            channel.writeAndFlush(envelope).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    sentEnvelopes.incrementAndGet();
                    return;
                }
                failedWrites.incrementAndGet();
                channels.remove(nextHop, future.channel());
                future.channel().close();
            });
        } catch (RuntimeException e) {
            failedWrites.incrementAndGet();
            channels.remove(nextHop, channel);
            throw e;
        }
    }

    public NettyTransportStats stats() {
        long active = channels.values().stream().filter(Channel::isActive).count();
        return new NettyTransportStats(
                Math.toIntExact(active),
                connectionAttempts.get(),
                connectionFailures.get(),
                sentEnvelopes.get(),
                failedWrites.get(),
                receivedEnvelopes.get(),
                inboundFailures.get()
        );
    }

    private Channel connect(ServiceId serviceId) {
        ServiceEndpoint endpoint = endpoints.endpointOf(serviceId);
        connectionAttempts.incrementAndGet();
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new EnvelopeChannelInitializer(codec, ignored -> {
                    }, receivedEnvelopes, inboundFailures));
            ChannelFuture future = bootstrap.connect(endpoint.host(), endpoint.port()).sync();
            Channel channel = future.channel();
            channel.closeFuture().addListener(ignored -> channels.remove(serviceId, channel));
            return channel;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectionFailures.incrementAndGet();
            throw new IllegalStateException("Interrupted while connecting to " + serviceId.wireName(), e);
        } catch (Exception e) {
            connectionFailures.incrementAndGet();
            throw new IllegalStateException("Failed to connect to " + serviceId.wireName(), e);
        }
    }

    @Override
    public void close() {
        channels.values().forEach(Channel::close);
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    /**
     * Netty 传输查找服务地址的最小视图。
     */
    @FunctionalInterface
    public interface ServiceRegistryView {
        ServiceEndpoint endpointOf(ServiceId serviceId);
    }

    private static final class EnvelopeChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final ClusterMessageHandler handler;
        private final ProtoClusterCodec codec;
        private final AtomicLong receivedEnvelopes;
        private final AtomicLong inboundFailures;

        private EnvelopeChannelInitializer(
                ProtoClusterCodec codec,
                ClusterMessageHandler handler,
                AtomicLong receivedEnvelopes,
                AtomicLong inboundFailures
        ) {
            this.codec = codec;
            this.handler = handler;
            this.receivedEnvelopes = receivedEnvelopes;
            this.inboundFailures = inboundFailures;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            channel.pipeline()
                    .addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_SIZE, 0, 4, 0, 4))
                    .addLast(new NettyClusterFrameDecoder(codec))
                    .addLast(new LengthFieldPrepender(4))
                    .addLast(new NettyClusterFrameEncoder(codec))
                    .addLast(new SimpleChannelInboundHandler<ClusterEnvelope>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext context, ClusterEnvelope envelope) {
                            receivedEnvelopes.incrementAndGet();
                            handler.onMessage(envelope);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
                            inboundFailures.incrementAndGet();
                            context.close();
                        }
                    });
        }
    }
}
