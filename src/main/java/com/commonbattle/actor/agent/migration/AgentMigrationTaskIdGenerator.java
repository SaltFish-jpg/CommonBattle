package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.time.Instant;

/**
 * Agent 迁移任务 ID 生成器。
 */
@FunctionalInterface
public interface AgentMigrationTaskIdGenerator {
    String nextId(AgentIdentity identity, AgentLocation source, AgentLocation target, Instant now);

    static AgentMigrationTaskIdGenerator defaultGenerator() {
        return (identity, source, target, now) -> identity.wireName()
                + "|" + source.serviceId().wireName()
                + "|" + target.serviceId().wireName()
                + "|" + now.toEpochMilli();
    }
}
