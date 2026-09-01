package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.network.ClusterEnvelope;

/**
 * 服务端 RPC 处理器。
 * 处理器运行在网络入口之后，复杂业务应继续投递到本服务 Actor，而不是在网络线程里直接结算。
 */
@FunctionalInterface
public interface RpcEndpointHandler {
    void handle(ClusterEnvelope request, RpcResponder responder);
}
