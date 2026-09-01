package com.commonbattle.cluster.netty;

import com.commonbattle.cluster.network.ClusterEnvelope;
import com.commonbattle.cluster.protocol.ProtoClusterCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

final class NettyClusterFrameEncoder extends MessageToByteEncoder<ClusterEnvelope> {
    private final ProtoClusterCodec codec;

    NettyClusterFrameEncoder(ProtoClusterCodec codec) {
        this.codec = codec;
    }

    @Override
    protected void encode(ChannelHandlerContext context, ClusterEnvelope envelope, ByteBuf out) {
        byte[] payload = codec.encode(envelope);
        out.writeBytes(payload);
    }
}
