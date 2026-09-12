package com.commonbattle.game.session;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 玩家客户端入站命令 Netty 解码器。
 */
public final class NettyPlayerClientCommandFrameDecoder extends ByteToMessageDecoder {
    private final ProtoPlayerClientCommandCodec codec;

    public NettyPlayerClientCommandFrameDecoder(ProtoPlayerClientCommandCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        out.add(codec.decode(bytes));
    }
}
