package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;

import java.util.Optional;

/**
 * 把进程内 ActorId 解析为全局 Agent 身份，供热点迁移计划器适配不同业务命名规则。
 */
@FunctionalInterface
public interface ActorIdentityResolver {
    Optional<AgentIdentity> resolve(String actorId);
}
