package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectoryLease;

import java.util.Objects;

/**
 * 按完整凭证释放 Agent owner 租约的请求。
 */
public record AgentDirectoryLeaseReleaseRequest(AgentDirectoryLease lease) {
    public AgentDirectoryLeaseReleaseRequest {
        Objects.requireNonNull(lease, "lease");
    }
}
