package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;

/**
 * RPC 调用级路由策略。
 * 实现类只修改调用选项，不能在这里执行业务逻辑或阻塞等待远端结果。
 */
@FunctionalInterface
public interface RpcRoutePolicy {
    RpcCallOptions optionsFor(RpcRequest<?> request, RpcCallOptions baseOptions);

    static RpcRoutePolicy none() {
        return (request, baseOptions) -> baseOptions;
    }
}
