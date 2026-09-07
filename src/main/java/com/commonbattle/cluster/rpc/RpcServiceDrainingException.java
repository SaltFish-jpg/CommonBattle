package com.commonbattle.cluster.rpc;

import com.commonbattle.cluster.ServiceId;

/**
 * 目标服务已进入本地排水时返回给调用方的 RPC 失败。
 */
public final class RpcServiceDrainingException extends RuntimeException {
    public RpcServiceDrainingException(ServiceId serviceId, String operation) {
        super("Service " + serviceId.wireName() + " is draining, rejected RPC " + operation);
    }
}
