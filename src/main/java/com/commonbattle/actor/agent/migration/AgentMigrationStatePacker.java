package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

/**
 * 源 Agent 迁移打包扩展点。
 * 实现会在源 Agent 邮箱内执行，因此可以直接读取本地内存状态并生成一致快照。
 */
@FunctionalInterface
public interface AgentMigrationStatePacker {
    AgentMigrationSnapshot pack(AgentIdentity identity, AgentLocation target, ActorContext context);
}
