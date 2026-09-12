package com.commonbattle.game.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 玩家客户端入站统一信封编码器，主要供测试客户端和压测工具复用。
 */
public final class NettyPlayerClientInboundFrameEncoder extends MessageToByteEncoder<PlayerClientInboundEnvelope> {
    private final ProtoPlayerClientInboundCodec codec;

    public NettyPlayerClientInboundFrameEncoder(ProtoPlayerClientInboundCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext context, PlayerClientInboundEnvelope envelope, ByteBuf out) {
        out.writeBytes(codec.encode(envelope));
    }
}
