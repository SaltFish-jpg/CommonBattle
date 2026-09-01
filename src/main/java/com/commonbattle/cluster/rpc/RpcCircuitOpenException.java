package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;

/**
 * RPC 熔断打开，调用被本地快速失败。
 */
public final class RpcCircuitOpenException extends RuntimeException {
    public RpcCircuitOpenException(RpcRequest<?> request) {
        super("RPC circuit open: " + request.target() + "." + request.operation());
    }
}
