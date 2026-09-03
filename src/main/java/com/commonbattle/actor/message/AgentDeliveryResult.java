package com.commonbattle.actor.message;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent 消息投递结果。
 * 调用方可依据 status 和 retryAfter 决定是否向客户端返回忙碌、排队重试或交给跨服 RPC。
 */
public record AgentDeliveryResult(AgentDeliveryStatus status, String reason, Duration retryAfter) {
    public AgentDeliveryResult {
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
        retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public boolean accepted() {
        return status == AgentDeliveryStatus.ACCEPTED || status == AgentDeliveryStatus.ROUTED_REMOTE;
    }

    public boolean retryable() {
        return status == AgentDeliveryStatus.MAILBOX_FULL
                || status == AgentDeliveryStatus.REMOTE_UNAVAILABLE
                || status == AgentDeliveryStatus.TIMEOUT
                || status == AgentDeliveryStatus.CIRCUIT_OPEN;
    }

    public static AgentDeliveryResult acceptedResult() {
        return new AgentDeliveryResult(AgentDeliveryStatus.ACCEPTED, "", Duration.ZERO);
    }

    public static AgentDeliveryResult routedRemote() {
        return new AgentDeliveryResult(AgentDeliveryStatus.ROUTED_REMOTE, "", Duration.ZERO);
    }

    public static AgentDeliveryResult mailboxFull() {
        return new AgentDeliveryResult(AgentDeliveryStatus.MAILBOX_FULL, "mailbox_full", Duration.ZERO);
    }

    public static AgentDeliveryResult systemClosed() {
        return new AgentDeliveryResult(AgentDeliveryStatus.SYSTEM_CLOSED, "system_closed", Duration.ZERO);
    }

    public static AgentDeliveryResult routeMissing() {
        return new AgentDeliveryResult(AgentDeliveryStatus.ROUTE_MISSING, "route_missing", Duration.ZERO);
    }

    public static AgentDeliveryResult remoteUnavailable(String reason) {
        return new AgentDeliveryResult(AgentDeliveryStatus.REMOTE_UNAVAILABLE,
                reason == null || reason.isBlank() ? "remote_unavailable" : reason,
                Duration.ZERO);
    }

    public static AgentDeliveryResult timeout(String reason) {
        return new AgentDeliveryResult(AgentDeliveryStatus.TIMEOUT,
                reason == null || reason.isBlank() ? "rpc_timeout" : reason,
                Duration.ZERO);
    }

    public static AgentDeliveryResult circuitOpen(String reason) {
        return new AgentDeliveryResult(AgentDeliveryStatus.CIRCUIT_OPEN,
                reason == null || reason.isBlank() ? "rpc_circuit_open" : reason,
                Duration.ZERO);
    }

    public static AgentDeliveryResult rejected(String reason, Duration retryAfter) {
        return new AgentDeliveryResult(AgentDeliveryStatus.REJECTED, reason, retryAfter);
    }
}
