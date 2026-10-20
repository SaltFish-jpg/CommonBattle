package com.commonbattle.actor.message;

/**
 * Agent 通信端口运行统计视图。
 */
@FunctionalInterface
public interface AgentMessagePortView {
    AgentMessagePortStats agentMessagePortStats();
}
