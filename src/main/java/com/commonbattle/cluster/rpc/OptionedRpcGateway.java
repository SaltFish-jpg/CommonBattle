package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;

/**
 * 支持单次调用治理选项的 RPC 出口。
 * 业务层仍依赖 RpcGateway；路由、灰度和幂等这类基础设施可在包装层传入更细的调用选项。
 */
public interface OptionedRpcGateway extends RpcGateway {
    <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options);
}
