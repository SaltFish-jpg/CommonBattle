package com.commonbattle.cluster.rpc;

import java.time.Duration;

/**
 * 可跨实例协调执行权的 RPC 幂等存储。
 * 实现必须以单键原子操作保证同一幂等键同时只有一个有效 owner，并允许 owner 失联后按租约接管。
 */
public interface CoordinatedRpcIdempotencyStore extends RpcIdempotencyStore {
    RpcIdempotencyClaim claim(RpcIdempotencyKey key, String ownerToken);

    boolean renew(RpcIdempotencyKey key, String ownerToken);

    boolean complete(RpcIdempotencyKey key, String ownerToken, RpcIdempotencyResult result);

    boolean release(RpcIdempotencyKey key, String ownerToken);

    Duration leaseDuration();
}
