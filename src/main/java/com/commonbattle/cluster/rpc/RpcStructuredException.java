package com.commonbattle.cluster.rpc;

import java.time.Duration;
import java.util.Objects;

/**
 * 可跨进程透传的 RPC 失败。
 * 服务端用它表达稳定错误码和重试时间，客户端收到后由 failure mapper 转成业务可理解的投递结果。
 */
public final class RpcStructuredException extends RuntimeException {
    public static final String REMOTE_ERROR = "remote_error";

    private final String code;
    private final Duration retryAfter;

    public RpcStructuredException(String code, String message, Duration retryAfter) {
        super(message == null ? "" : message);
        this.code = normalizeCode(code);
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public String code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public static RpcStructuredException from(ClusterRpcGateway.RpcError error) {
        Objects.requireNonNull(error, "error");
        return new RpcStructuredException(error.code(), error.message(), Duration.ofMillis(error.retryAfterMillis()));
    }

    public static RpcStructuredException rejected(String reason, Duration retryAfter) {
        return new RpcStructuredException(reason, reason, retryAfter);
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return REMOTE_ERROR;
        }
        return code;
    }
}
