package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.agent.AgentLocation;

/**
 * Agent 生命周期动作完成回调。
 * 用于迁入恢复这类必须等目标邮箱执行完毕后，才能向源服确认的流程。
 */
public interface AgentLifecycleResultCallback {
    void succeeded(AgentLocation location);

    void failed(Throwable error);

    static AgentLifecycleResultCallback ignore() {
        return new AgentLifecycleResultCallback() {
            @Override
            public void succeeded(AgentLocation location) {
            }

            @Override
            public void failed(Throwable error) {
            }
        };
    }
}
