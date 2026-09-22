package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

/**
 * 热点迁移计划器使用的迁移提交入口。
 * 生产环境通常由 AgentMigrationCoordinator 实现，测试可替换为轻量记录器。
 */
@FunctionalInterface
public interface ActorHotspotMigrationSubmitter {
    boolean migrate(AgentIdentity identity, AgentLocation target, AgentMigrationStatePacker packer);
}
