package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.cluster.ServiceDescriptor;

/**
 * 根据目标服务重写迁入 ActorId；当 ActorId 包含本机 node 名时必须提供业务实现。
 */
@FunctionalInterface
public interface MigrationTargetActorResolver {
    String resolve(String sourceActorId, AgentIdentity identity, ServiceDescriptor targetService);
}
