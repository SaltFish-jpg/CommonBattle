package com.commonbattle.actor.message;

/**
 * Agent 消息投递结果状态。
 * 用于把本服邮箱、远程 RPC、服务关闭等失败统一成可观测、可重试决策的结果。
 */
public enum AgentDeliveryStatus {
    ACCEPTED,
    ROUTED_REMOTE,
    MAILBOX_FULL,
    SYSTEM_CLOSED,
    ROUTE_MISSING,
    REMOTE_UNAVAILABLE,
    TIMEOUT,
    CIRCUIT_OPEN,
    REJECTED
}
