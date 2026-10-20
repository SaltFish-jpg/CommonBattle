package com.commonbattle.game.session;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 跟踪玩家网关所有异步出站写入，供停机排空等待最后一批客户端响应完成。
 */
public final class NettyPlayerWriteTracker {
    private final AtomicLong inFlightWrites = new AtomicLong();

    public ChannelFuture writeAndFlush(Channel channel, Object message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");
        return trackWrite(() -> channel.writeAndFlush(message));
    }

    ChannelFuture trackWrite(Supplier<ChannelFuture> write) {
        Objects.requireNonNull(write, "write");
        inFlightWrites.incrementAndGet();
        try {
            ChannelFuture future = Objects.requireNonNull(write.get(), "write future");
            future.addListener((ChannelFutureListener) ignored -> settle());
            return future;
        } catch (RuntimeException e) {
            settle();
            throw e;
        }
    }

    public long inFlightWrites() {
        return inFlightWrites.get();
    }

    private void settle() {
        inFlightWrites.updateAndGet(current -> Math.max(0, current - 1));
    }
}
