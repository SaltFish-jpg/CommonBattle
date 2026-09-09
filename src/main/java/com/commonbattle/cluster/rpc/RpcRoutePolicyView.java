package com.commonbattle.cluster.rpc;

/**
 * 可被运维探针读取的 RPC 路由策略视图。
 */
public interface RpcRoutePolicyView {
    RpcRouteStats stats();
}
