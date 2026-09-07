package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceDescriptor;
import com.commonbattle.cluster.ServiceKind;

import java.util.List;

/**
 * RPC 目标选择扩展点。
 * 当某类业务需要读取 payload 或服务 metadata 做专用选路时实现它；普通 RPC 仍使用网关默认负载选择。
 */
public interface RpcTargetSelector {
    boolean supports(RpcRequest<?> request, ServiceKind kind);

    ServiceDescriptor select(RpcRequest<?> request, List<ServiceDescriptor> candidates);
}
