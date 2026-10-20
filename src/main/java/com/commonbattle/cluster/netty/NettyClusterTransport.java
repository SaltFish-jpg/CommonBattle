package com.commonbattle.cluster.netty;

import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceEndpoint;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.network.ClusterMessageHandler;
import com.commonbattle.cluster.network.ClusterSendCallback;
import com.commonbattle.cluster.network.ClusterTransport;
import com.commonbattle.cluster.network.ClusterTransportException;
import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Netty 的跨服传输实现。
 * 建连和写入均在 Netty 事件循环异步完成，调用它的 Actor 工作线程不会等待网络 I/O。
 */
public final class NettyClusterTransport implements ClusterTransport {
    private static final int MAX_FRAME_SIZE = 1024 * 1024;
    private static final long CLOSE_TIMEOUT_MILLIS = 1_000;

    private final ServiceRegistryView endpoints;
    private final ProtoClusterCodec codec;
    private final NettyClusterTransportConfig config;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private final Bootstrap clientBootstrap;
    private final Map<ServiceId, ConnectionSlot> connections = new ConcurrentHashMap<>();
    private final AtomicLong connectionAttempts = new AtomicLong();
    private final AtomicLong connectionFailures = new AtomicLong();
    private final AtomicLong sentEnvelopes = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();
    private final AtomicLong inFlightWrites = new AtomicLong();
    private final AtomicLong receivedEnvelopes = new AtomicLong();
    private final AtomicLong inboundFailures = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Channel serverChannel;

    public NettyClusterTransport(ServiceRegistryView endpoints) {
        this(endpoints, PayloadCodecRegistry.commonDefaults());
    }

    public NettyClusterTransport(ServiceRegistryView endpoints, PayloadCodecRegistry payloadCodecs) {
        this(endpoints, payloadCodecs, NettyClusterTransportConfig.defaults());
    }

    public NettyClusterTransport(
            ServiceRegistryView endpoints,
            PayloadCodecRegistry payloadCodecs,
            NettyClusterTransportConfig config
    ) {
        this(endpoints, payloadCodecs, config, new NioEventLoopGroup(1), new NioEventLoopGroup());
    }

    NettyClusterTransport(
            ServiceRegistryView endpoints,
            PayloadCodecRegistry payloadCodecs,
            NettyClusterTransportConfig config,
            NioEventLoopGroup bossGroup,
            NioEventLoopGroup workerGroup
    ) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.codec = new ProtoClusterCodec(Objects.requireNonNull(payloadCodecs, "payloadCodecs"));
        this.config = Objects.requireNonNull(config, "config");
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.clientBootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(config.connectTimeout().toMillis()))
                .handler(new EnvelopeChannelInitializer(codec, ignored -> {
                }, receivedEnvelopes, inboundFailures));
    }

    @Override
    public void bind(ServiceDescriptor local, ClusterMessageHandler handler) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(handler, "handler");
        ensureOpen();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
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
        if (closed.get()) {
            failedWrites.incrementAndGet();
            throw new IllegalStateException("Netty transport is closed");
        }
        send(nextHop, envelope, ClusterSendCallback.NOOP);
    }

    @Override
    public void send(ServiceId nextHop, ClusterEnvelope envelope, ClusterSendCallback callback) {
        Objects.requireNonNull(nextHop, "nextHop");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            failedWrites.incrementAndGet();
            notifyFailure(callback, closedFailure(nextHop));
            return;
        }
        connections.computeIfAbsent(nextHop, ConnectionSlot::new).submit(new PendingWrite(envelope, callback));
    }

    public NettyTransportStats stats() {
        long active = connections.values().stream().filter(ConnectionSlot::isActive).count();
        return new NettyTransportStats(
                Math.toIntExact(active),
                connectionAttempts.get(),
                connectionFailures.get(),
                sentEnvelopes.get(),
                failedWrites.get(),
                inFlightWrites.get(),
                receivedEnvelopes.get(),
                inboundFailures.get()
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connections.values().forEach(ConnectionSlot::close);
        if (serverChannel != null) {
            closeChannel(serverChannel);
        }
        bossGroup.shutdownGracefully(0, CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
        workerGroup.shutdownGracefully(0, CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Netty transport is closed");
        }
    }

    private void write(ServiceId nextHop, Channel channel, PendingWrite pending) {
        try {
            channel.writeAndFlush(pending.envelope()).addListener((ChannelFutureListener) future -> {
                try {
                    if (future.isSuccess()) {
                        sentEnvelopes.incrementAndGet();
                        notifySuccess(pending.callback());
                        return;
                    }
                    failedWrites.incrementAndGet();
                    connections.computeIfPresent(nextHop, (ignored, slot) -> {
                        slot.disconnected(future.channel());
                        return slot;
                    });
                    future.channel().close();
                    notifyFailure(pending.callback(), new ClusterTransportException(
                            nextHop,
                            "Failed to write cluster envelope to " + nextHop.wireName(),
                            future.cause()
                    ));
                } finally {
                    settleWrite();
                }
            });
        } catch (RuntimeException error) {
            failedWrites.incrementAndGet();
            connections.computeIfPresent(nextHop, (ignored, slot) -> {
                slot.disconnected(channel);
                return slot;
            });
            settleWrite();
            notifyFailure(pending.callback(), new ClusterTransportException(
                    nextHop,
                    "Failed to submit cluster envelope to " + nextHop.wireName(),
                    error
            ));
        }
    }

    private void failWrites(ServiceId nextHop, List<PendingWrite> writes, ClusterTransportException error) {
        for (PendingWrite pending : writes) {
            failedWrites.incrementAndGet();
            settleWrite();
            notifyFailure(pending.callback(), error);
        }
    }

    private void settleWrite() {
        inFlightWrites.updateAndGet(current -> Math.max(0, current - 1));
    }

    private static void notifySuccess(ClusterSendCallback callback) {
        try {
            callback.success();
        } catch (Throwable ignored) {
        }
    }

    private static void notifyFailure(ClusterSendCallback callback, Throwable error) {
        try {
            callback.failure(error);
        } catch (Throwable ignored) {
        }
    }

    private static ClusterTransportException closedFailure(ServiceId nextHop) {
        return new ClusterTransportException(nextHop, "Netty transport is closed");
    }

    private static void closeChannel(Channel channel) {
        channel.close().awaitUninterruptibly(CLOSE_TIMEOUT_MILLIS);
    }

    /**
     * Netty 传输查找服务地址的最小视图。
     */
    @FunctionalInterface
    public interface ServiceRegistryView {
        ServiceEndpoint endpointOf(ServiceId serviceId);
    }

    private final class ConnectionSlot {
        private final ServiceId nextHop;
        private final ArrayDeque<PendingWrite> pending = new ArrayDeque<>();
        private Channel channel;
        private ChannelFuture connectingFuture;
        private boolean connecting;
        private long reconnectAfterNanos;

        private ConnectionSlot(ServiceId nextHop) {
            this.nextHop = nextHop;
        }

        void submit(PendingWrite write) {
            Channel activeChannel;
            boolean startConnect = false;
            ClusterTransportException rejection = null;
            synchronized (this) {
                if (closed.get()) {
                    activeChannel = null;
                    rejection = closedFailure(nextHop);
                } else if (channel != null && channel.isActive()) {
                    activeChannel = channel;
                    inFlightWrites.incrementAndGet();
                } else if (!connecting && System.nanoTime() < reconnectAfterNanos) {
                    activeChannel = null;
                    rejection = new ClusterTransportException(
                            nextHop,
                            "Reconnect backoff is active for " + nextHop.wireName()
                    );
                } else if (pending.size() >= config.maxPendingWritesPerTarget()) {
                    activeChannel = null;
                    rejection = new ClusterTransportException(
                            nextHop,
                            "Pending cluster writes are full for " + nextHop.wireName()
                    );
                } else {
                    activeChannel = null;
                    pending.addLast(write);
                    inFlightWrites.incrementAndGet();
                    if (!connecting) {
                        connecting = true;
                        startConnect = true;
                    }
                }
            }
            if (rejection != null) {
                failedWrites.incrementAndGet();
                notifyFailure(write.callback(), rejection);
                return;
            }
            if (activeChannel != null) {
                NettyClusterTransport.this.write(nextHop, activeChannel, write);
                return;
            }
            if (startConnect) {
                connect();
            }
        }

        boolean isActive() {
            synchronized (this) {
                return channel != null && channel.isActive();
            }
        }

        void connect() {
            ServiceEndpoint endpoint;
            try {
                endpoint = Objects.requireNonNull(endpoints.endpointOf(nextHop), "endpoint");
            } catch (RuntimeException error) {
                connectionFailed(new ClusterTransportException(
                        nextHop,
                        "No endpoint available for " + nextHop.wireName(),
                        error
                ));
                return;
            }
            connectionAttempts.incrementAndGet();
            ChannelFuture future;
            try {
                future = clientBootstrap.connect(endpoint.host(), endpoint.port());
            } catch (RuntimeException error) {
                connectionFailed(new ClusterTransportException(
                        nextHop,
                        "Failed to start connection to " + nextHop.wireName(),
                        error
                ));
                return;
            }
            synchronized (this) {
                connectingFuture = future;
            }
            future.addListener((ChannelFutureListener) completed -> {
                if (!completed.isSuccess()) {
                    connectionFailed(new ClusterTransportException(
                            nextHop,
                            "Failed to connect to " + nextHop.wireName(),
                            completed.cause()
                    ));
                    return;
                }
                connected(completed.channel());
            });
        }

        void connected(Channel connectedChannel) {
            List<PendingWrite> writes;
            synchronized (this) {
                connecting = false;
                connectingFuture = null;
                if (closed.get()) {
                    writes = drainPending();
                } else {
                    channel = connectedChannel;
                    reconnectAfterNanos = 0;
                    writes = drainPending();
                }
            }
            if (closed.get()) {
                connectedChannel.close();
                failWrites(nextHop, writes, closedFailure(nextHop));
                return;
            }
            connectedChannel.closeFuture().addListener(ignored -> disconnected(connectedChannel));
            writes.forEach(write -> NettyClusterTransport.this.write(nextHop, connectedChannel, write));
        }

        void connectionFailed(ClusterTransportException failure) {
            List<PendingWrite> writes;
            synchronized (this) {
                connecting = false;
                connectingFuture = null;
                reconnectAfterNanos = System.nanoTime() + config.reconnectBackoff().toNanos();
                writes = drainPending();
            }
            connectionFailures.incrementAndGet();
            failWrites(nextHop, writes, failure);
        }

        void disconnected(Channel disconnectedChannel) {
            synchronized (this) {
                if (channel == disconnectedChannel) {
                    channel = null;
                }
            }
        }

        void close() {
            Channel activeChannel;
            ChannelFuture connectAttempt;
            List<PendingWrite> writes;
            synchronized (this) {
                activeChannel = channel;
                channel = null;
                connectAttempt = connectingFuture;
                connectingFuture = null;
                connecting = false;
                writes = drainPending();
            }
            failWrites(nextHop, writes, closedFailure(nextHop));
            if (connectAttempt != null) {
                connectAttempt.cancel(false);
                connectAttempt.channel().close();
            }
            if (activeChannel != null) {
                closeChannel(activeChannel);
            }
        }

        private List<PendingWrite> drainPending() {
            List<PendingWrite> writes = new ArrayList<>(pending);
            pending.clear();
            return writes;
        }
    }

    private record PendingWrite(ClusterEnvelope envelope, ClusterSendCallback callback) {
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
