package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectoryLease;

import java.util.Objects;

/**
 * 远程续租 Agent owner 的请求。
 */
public record AgentDirectoryLeaseRenewRequest(AgentDirectoryLease lease, long leaseTtlMillis) {
    public AgentDirectoryLeaseRenewRequest {
        Objects.requireNonNull(lease, "lease");
        if (leaseTtlMillis <= 0) {
            throw new IllegalArgumentException("leaseTtlMillis must be positive");
        }
    }
}
