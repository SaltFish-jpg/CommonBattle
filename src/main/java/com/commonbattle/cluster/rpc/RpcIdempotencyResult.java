package com.commonbattle.cluster.rpc;

/**
 * RPC 服务端已完成的幂等响应。
 * success 表示应按成功响应返回，否则按失败响应返回。
 */
public record RpcIdempotencyResult(boolean success, Object payload) {
    public static RpcIdempotencyResult success(Object payload) {
        return new RpcIdempotencyResult(true, payload);
    }

    public static RpcIdempotencyResult failure(Object payload) {
        return new RpcIdempotencyResult(false, payload);
    }
}
