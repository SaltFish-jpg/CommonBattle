package com.commonbattle.game.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 玩家客户端入站命令测试和本地客户端使用的编码器。
 */
public final class NettyPlayerClientCommandFrameEncoder extends MessageToByteEncoder<PlayerClientCommandEnvelope> {
    private final ProtoPlayerClientCommandCodec codec;

    public NettyPlayerClientCommandFrameEncoder(ProtoPlayerClientCommandCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext context, PlayerClientCommandEnvelope envelope, ByteBuf out) {
        out.writeBytes(codec.encode(envelope));
    }
}
