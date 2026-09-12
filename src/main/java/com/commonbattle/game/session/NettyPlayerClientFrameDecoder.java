package com.commonbattle.game.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 玩家客户端出站信封 Netty 解码器。
 */
public final class NettyPlayerClientFrameDecoder extends ByteToMessageDecoder {
    private final ProtoPlayerClientCodec codec;

    public NettyPlayerClientFrameDecoder(ProtoPlayerClientCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        out.add(codec.decode(bytes));
    }
}
