package com.commonbattle.example.cross;

import com.commonbattle.cluster.protocol.PayloadCodec;
import com.commonbattle.cluster.rpc.ClusterRpcGateway;

import java.nio.charset.StandardCharsets;

final class RpcErrorPayloadCodec implements PayloadCodec<ClusterRpcGateway.RpcError> {
    @Override
    public String typeName() {
        return ClusterRpcGateway.RpcError.class.getName();
    }

    @Override
    public Class<ClusterRpcGateway.RpcError> javaType() {
        return ClusterRpcGateway.RpcError.class;
    }

    @Override
    public byte[] encode(ClusterRpcGateway.RpcError payload) {
        return payload.message().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ClusterRpcGateway.RpcError decode(byte[] bytes) {
        return new ClusterRpcGateway.RpcError(new String(bytes, StandardCharsets.UTF_8));
    }
}
