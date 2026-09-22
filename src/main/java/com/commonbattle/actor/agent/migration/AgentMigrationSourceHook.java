package com.commonbattle.actor.agent.migration;

/**
 * 源服务迁移时序扩展点。
 * 目录已经切到目标后可清理源端内存句柄；如果后续目标接收失败并回滚，可用同一快照恢复源端句柄。
 */
public interface AgentMigrationSourceHook {
    void sourceMoved(AgentMigrationTask task);

    void rollbackRestored(AgentMigrationTask task);

    static AgentMigrationSourceHook noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements AgentMigrationSourceHook {
        INSTANCE;

        @Override
        public void sourceMoved(AgentMigrationTask task) {
        }

        @Override
        public void rollbackRestored(AgentMigrationTask task) {
        }
    }
}
