package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectoryLease;
import com.commonbattle.actor.agent.AgentLocation;

import java.util.Objects;

/**
 * 按当前凭证转移 Agent owner 租约的请求。
 */
public record AgentDirectoryLeaseTransferRequest(
        AgentDirectoryLease lease,
        AgentLocation nextLocation,
        String nextOwnerSession,
        long leaseTtlMillis
) {
    public AgentDirectoryLeaseTransferRequest {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(nextLocation, "nextLocation");
        Objects.requireNonNull(nextOwnerSession, "nextOwnerSession");
        if (nextOwnerSession.isBlank()) {
            throw new IllegalArgumentException("nextOwnerSession must not be blank");
        }
        if (leaseTtlMillis <= 0) {
            throw new IllegalArgumentException("leaseTtlMillis must be positive");
        }
    }
}
