package com.commonbattle.actor.agent;

/**
 * Agent owner 确认失去租约后的通知边界。
 * 实现只能向对应 Actor 邮箱投递 fencing 消息，不应在租约调度线程执行游戏业务。
 */
@FunctionalInterface
public interface AgentLeaseLossHandler {
    void leaseLost(AgentDirectoryLease lease);
}
