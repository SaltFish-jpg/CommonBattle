package com.commonbattle.cluster.rpc;

/**
 * RPC 服务端幂等结果存储。
 * 默认可用内存实现，生产环境可替换为 DB、Redis 或带落盘能力的实现以跨重启保留去重结果。
 */
public interface RpcIdempotencyStore {
    RpcIdempotencyResult get(RpcIdempotencyKey key);

    void put(RpcIdempotencyKey key, RpcIdempotencyResult result);

    int size();
}
