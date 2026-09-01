package com.commonbattle.actor.agent.lifecycle;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.time.Instant;
import java.util.Objects;

/**
 * Agent 生命周期记录。
 * 用于观测本服务内真实 owner 的状态，以及迁移后指向的新位置。
 */
public record AgentLifecycleRecord(
        AgentIdentity identity,
        AgentLocation location,
        AgentLifecycleState state,
        Instant updatedAt
) {
    public AgentLifecycleRecord {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
