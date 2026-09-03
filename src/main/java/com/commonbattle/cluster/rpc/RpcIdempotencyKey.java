package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.ServiceId;

import java.util.Objects;

/**
 * RPC 服务端幂等键。
 * 同一调用方、同一 operation、同一业务幂等 key 命中同一条已完成响应。
 */
public record RpcIdempotencyKey(ServiceId source, String operation, String key) {
    public RpcIdempotencyKey {
        Objects.requireNonNull(source, "source");
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key required");
        }
    }
}
