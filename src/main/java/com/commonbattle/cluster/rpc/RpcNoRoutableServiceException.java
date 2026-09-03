package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceKind;

/**
 * RPC 目标服务不存在或全部摘流时抛出的明确异常。
 */
public final class RpcNoRoutableServiceException extends RuntimeException {
    public RpcNoRoutableServiceException(ServiceKind target, String operation) {
        super("No routable service registered for " + target + " operation " + operation);
    }

    public RpcNoRoutableServiceException(RpcRequest<?> request) {
        this(ServiceKind.valueOf(request.target()), request.operation());
    }
}
