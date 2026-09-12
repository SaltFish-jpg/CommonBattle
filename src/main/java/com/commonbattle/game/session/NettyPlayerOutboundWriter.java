package com.commonbattle.game.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Netty Channel 的玩家出站写出器。
 * 它只做非阻塞写入，写失败统计留给连接层处理，业务 Actor 不等待网络结果。
 */
public final class NettyPlayerOutboundWriter implements PlayerOutboundWriter {
    private final Channel channel;
    private final AtomicLong acceptedWrites = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();

    public NettyPlayerOutboundWriter(Channel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public boolean write(PlayerOutboundMessage message) {
        Objects.requireNonNull(message, "message");
        if (!channel.isActive() || !channel.isWritable()) {
            failedWrites.incrementAndGet();
            return false;
        }
        try {
            channel.writeAndFlush(PlayerClientEnvelope.from(message)).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    acceptedWrites.incrementAndGet();
                    return;
                }
                failedWrites.incrementAndGet();
                future.channel().close();
            });
            return true;
        } catch (RuntimeException e) {
            failedWrites.incrementAndGet();
            return false;
        }
    }

    public NettyPlayerOutboundWriterStats stats() {
        return new NettyPlayerOutboundWriterStats(
                channel.isActive(),
                channel.isWritable(),
                acceptedWrites.get(),
                failedWrites.get()
        );
    }
}
