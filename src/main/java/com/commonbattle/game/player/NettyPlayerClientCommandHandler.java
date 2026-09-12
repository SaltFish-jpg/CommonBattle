package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerClientCommandEnvelope;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家客户端命令 Netty handler。
 * 入站线程只解码和投递，不运行玩家业务逻辑。
 */
public final class NettyPlayerClientCommandHandler extends SimpleChannelInboundHandler<PlayerClientCommandEnvelope> {
    private final PlayerClientCommandAcceptor ingress;
    private final AtomicLong acceptedCommands = new AtomicLong();
    private final AtomicLong failedCommands = new AtomicLong();

    public NettyPlayerClientCommandHandler(PlayerClientCommandAcceptor ingress) {
        this.ingress = Objects.requireNonNull(ingress, "ingress");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, PlayerClientCommandEnvelope envelope) {
        try {
            ingress.accept(envelope);
            acceptedCommands.incrementAndGet();
        } catch (RuntimeException e) {
            failedCommands.incrementAndGet();
            context.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        failedCommands.incrementAndGet();
        context.close();
    }

    public NettyPlayerClientCommandHandlerStats stats() {
        return new NettyPlayerClientCommandHandlerStats(acceptedCommands.get(), failedCommands.get());
    }
}
