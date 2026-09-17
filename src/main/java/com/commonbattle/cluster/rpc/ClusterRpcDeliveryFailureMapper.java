package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.message.AgentDeliveryResult;
import com.commonbattle.actor.message.RemoteCallFailureMapper;

import java.util.Locale;

/**
 * 跨服 RPC 失败映射器。
 * 将网关容量保护、超时、熔断和链路不可达统一转成 AgentDeliveryResult，供上层做降级与客户端返回。
 */
public final class ClusterRpcDeliveryFailureMapper implements RemoteCallFailureMapper {
    @Override
    public AgentDeliveryResult map(Throwable error) {
        if (error instanceof RpcTimeoutException) {
            return AgentDeliveryResult.timeout(error.getMessage());
        }
        if (error instanceof RpcCircuitOpenException) {
            return AgentDeliveryResult.circuitOpen(error.getMessage());
        }
        if (error instanceof RpcRejectedException) {
            return AgentDeliveryResult.rejected("rpc_rejected", java.time.Duration.ZERO);
        }
        if (error instanceof RpcStructuredException structured) {
            if (isAdmissionRejected(structured)) {
                return AgentDeliveryResult.rejected(structured.getMessage(), structured.retryAfter());
            }
            return AgentDeliveryResult.remoteUnavailable(structured.getMessage());
        }
        if (error instanceof RpcNoRoutableServiceException) {
            return AgentDeliveryResult.remoteUnavailable(error.getMessage());
        }
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.toLowerCase(Locale.ROOT).contains("closed")) {
            return AgentDeliveryResult.systemClosed();
        }
        return AgentDeliveryResult.remoteUnavailable(message);
    }

    private static boolean isAdmissionRejected(RpcStructuredException error) {
        String code = error.code().toLowerCase(Locale.ROOT);
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return code.startsWith("mailbox_pressure")
                || code.equals("rate_limited")
                || message.startsWith("mailbox_pressure")
                || message.equals("rate_limited");
    }
}
