package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.ServiceId;

/**
 * RPC 网关关闭时用于终止未完成请求，调用方可将失败重新投递回所属 Actor 邮箱。
 */
public final class RpcGatewayClosedException extends IllegalStateException {
    public RpcGatewayClosedException(ServiceId serviceId) {
        super("RPC gateway is closed: " + serviceId.wireName());
    }
}
