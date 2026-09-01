package com.commonbattle.cluster.rpc;

import java.time.Duration;
import java.util.Objects;

/**
 * RPC 重试策略。
 * 自动重试应只用于只读或带幂等 key 的请求，避免副作用请求被重复执行。
 */
public record RpcRetryPolicy(int maxAttempts, Duration retryDelay) {
    public RpcRetryPolicy {
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
    }

    public static RpcRetryPolicy noRetry() {
        return new RpcRetryPolicy(1, Duration.ZERO);
    }
}
