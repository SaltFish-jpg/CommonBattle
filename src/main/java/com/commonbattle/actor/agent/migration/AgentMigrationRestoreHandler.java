package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorContext;

/**
 * 迁移快照恢复扩展点。
 * Game/Scene 业务实现它，在目标 Actor 邮箱内反序列化并挂载玩家、场景或联盟状态。
 */
@FunctionalInterface
public interface AgentMigrationRestoreHandler {
    void restore(AgentMigrationAcceptRequest request, ActorContext context);

    static AgentMigrationRestoreHandler noop() {
        return (request, context) -> {
        };
    }
}
