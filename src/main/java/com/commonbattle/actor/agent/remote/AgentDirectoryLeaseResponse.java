package com.commonbattle.actor.agent.remote;

import com.commonbattle.actor.agent.AgentDirectoryLease;

import java.util.Optional;

/**
 * Agent owner 租约响应；空值表示当前 owner 冲突、过期或凭证已失效。
 */
public record AgentDirectoryLeaseResponse(AgentDirectoryLease lease) {
    public Optional<AgentDirectoryLease> leased() {
        return Optional.ofNullable(lease);
    }
}
