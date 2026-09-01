package com.commonbattle.actor.agent.lifecycle;

/**
 * 本服务内 Agent 生命周期状态。
 */
public enum AgentLifecycleState {
    ACTIVE,
    PASSIVATING,
    PASSIVATED,
    MIGRATING,
    MIGRATED,
    CLOSED
}
