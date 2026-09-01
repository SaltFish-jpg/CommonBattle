package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.ActorContext;

/**
 * Agent 生命周期动作。
 * 动作会被投递到目标 Agent 邮箱执行，用于保存状态、释放资源或迁移前打包。
 */
@FunctionalInterface
public interface AgentLifecycleAction {
    void run(ActorContext context);

    static AgentLifecycleAction none() {
        return ignored -> {
        };
    }
}
