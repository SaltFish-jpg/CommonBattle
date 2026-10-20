package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.util.Objects;

/**
 * 远程获取 Agent owner 租约的请求。
 */
public record AgentDirectoryLeaseAcquireRequest(
        AgentIdentity identity,
        AgentLocation location,
        String ownerSession,
        long leaseTtlMillis
) {
    public AgentDirectoryLeaseAcquireRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(ownerSession, "ownerSession");
        if (ownerSession.isBlank()) {
            throw new IllegalArgumentException("ownerSession must not be blank");
        }
        if (leaseTtlMillis <= 0) {
            throw new IllegalArgumentException("leaseTtlMillis must be positive");
        }
    }
}
