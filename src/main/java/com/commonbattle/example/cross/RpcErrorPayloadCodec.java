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
        String text = payload.code() + "\n" + payload.retryAfterMillis() + "\n" + payload.message();
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ClusterRpcGateway.RpcError decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int first = text.indexOf('\n');
        if (first < 0) {
            return new ClusterRpcGateway.RpcError(text);
        }
        int second = text.indexOf('\n', first + 1);
        if (second < 0) {
            return new ClusterRpcGateway.RpcError(text);
        }
        String code = text.substring(0, first);
        long retryAfterMillis = parseRetryAfterMillis(text.substring(first + 1, second));
        String message = text.substring(second + 1);
        return new ClusterRpcGateway.RpcError(code, message, retryAfterMillis);
    }

    private static long parseRetryAfterMillis(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
