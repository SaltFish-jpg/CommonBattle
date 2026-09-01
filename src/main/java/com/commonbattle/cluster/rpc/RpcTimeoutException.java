package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;

import java.time.Duration;

/**
 * 跨服 RPC 超时。
 */
public final class RpcTimeoutException extends RuntimeException {
    public RpcTimeoutException(RpcRequest<?> request, Duration timeout) {
        super("RPC timeout after " + timeout.toMillis() + "ms: " + request.target() + "." + request.operation());
    }
}
