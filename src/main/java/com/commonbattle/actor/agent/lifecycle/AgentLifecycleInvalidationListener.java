package com.commonbattle.actor.agent.lifecycle;

/**
 * 在本地 Agent 不再可接收业务时清理 Manager、索引或只读缓存中的进程内引用。
 */
@FunctionalInterface
public interface AgentLifecycleInvalidationListener {
    void invalidated(AgentLifecycleRecord record);
}
