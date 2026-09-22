package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Duration;

/**
 * 跨服 RPC 超时。
 */
public final class RpcTimeoutException extends RuntimeException {
    private final RpcRequest<?> request;
    private final Duration timeout;

    public RpcTimeoutException(RpcRequest<?> request, Duration timeout) {
        super("RPC timeout after " + timeout.toMillis() + "ms: " + request.target() + "." + request.operation());
        this.request = request;
        this.timeout = timeout;
    }

    public RpcRequest<?> request() {
        return request;
    }

    public Duration timeout() {
        return timeout;
    }
}
