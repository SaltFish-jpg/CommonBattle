package com.commonbattle.cluster.rpc;

import java.time.Duration;
import java.util.Objects;

/**
 * 单次跨服 RPC 调用选项。
 * idempotencyKey 用于业务重试去重；同一调用方、operation 和 key 会复用服务端已完成响应。
 */
public record RpcCallOptions(Duration timeout, String idempotencyKey) {
    public RpcCallOptions {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static RpcCallOptions of(Duration timeout) {
        return new RpcCallOptions(timeout, "");
    }

    public RpcCallOptions withIdempotencyKey(String key) {
        return new RpcCallOptions(timeout, key);
    }
}
