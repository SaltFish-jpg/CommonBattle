package com.commonbattle.cluster.rpc;

/**
 * 服务端 RPC 响应出口。
 */
public interface RpcResponder {
    void success(Object payload);

    void failure(Throwable error);
}
