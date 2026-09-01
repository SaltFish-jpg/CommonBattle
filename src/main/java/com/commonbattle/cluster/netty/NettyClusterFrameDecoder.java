package com.commonbattle.cluster.netty;

import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

final class NettyClusterFrameDecoder extends ByteToMessageDecoder {
    private final ProtoClusterCodec codec;

    NettyClusterFrameDecoder(ProtoClusterCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) {
        byte[] bytes = new byte[in.readableBytes()];
        in.readBytes(bytes);
        out.add(codec.decode(bytes));
    }
}
