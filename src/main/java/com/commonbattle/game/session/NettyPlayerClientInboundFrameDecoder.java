package com.commonbattle.game.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 玩家客户端入站统一信封解码器。
 */
public final class NettyPlayerClientInboundFrameDecoder extends ByteToMessageDecoder {
    private final ProtoPlayerClientInboundCodec codec;

    public NettyPlayerClientInboundFrameDecoder(ProtoPlayerClientInboundCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        out.add(codec.decode(bytes));
    }
}
