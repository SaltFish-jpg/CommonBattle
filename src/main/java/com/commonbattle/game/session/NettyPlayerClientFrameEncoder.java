package com.commonbattle.game.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 玩家客户端出站信封 Netty 编码器。
 */
public final class NettyPlayerClientFrameEncoder extends MessageToByteEncoder<PlayerClientEnvelope> {
    private final ProtoPlayerClientCodec codec;

    public NettyPlayerClientFrameEncoder(ProtoPlayerClientCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext context, PlayerClientEnvelope envelope, ByteBuf out) {
        out.writeBytes(codec.encode(envelope));
    }
}
