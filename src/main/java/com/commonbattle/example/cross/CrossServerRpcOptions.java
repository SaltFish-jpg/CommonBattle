package com.commonbattle.example.cross;

import java.time.Duration;
import java.util.Objects;

/**
 * 跨服 Actor RPC 的单次调用治理选项。
 * 用于给玩家、场景等业务实体的远程请求统一附加超时和幂等键。
 */
public record CrossServerRpcOptions(Duration timeout, String idempotencyKey) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    public CrossServerRpcOptions {
        Objects.requireNonNull(timeout, "timeout");
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static CrossServerRpcOptions defaults() {
        return new CrossServerRpcOptions(DEFAULT_TIMEOUT, "");
    }

    public CrossServerRpcOptions withIdempotencyKey(String key) {
        return new CrossServerRpcOptions(timeout, key);
    }
}
