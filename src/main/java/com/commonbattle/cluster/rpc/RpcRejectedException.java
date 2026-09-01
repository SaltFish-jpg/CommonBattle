package com.commonbattle.cluster.rpc;

/**
 * 跨服 RPC 因网关容量保护被拒绝。
 */
public final class RpcRejectedException extends RuntimeException {
    public RpcRejectedException(int maxPendingRequests) {
        super("RPC pending limit exceeded: " + maxPendingRequests);
    }
}
