package com.commonbattle.actor.agent;

import java.time.Duration;
import java.util.Optional;

/**
 * 支持 owner 租约和 fencing token 的 Agent 目录扩展。
 * owner 进程应周期续租；续租失败后必须停止该 Agent 的业务执行和持久化写入。
 */
public interface LeasedAgentDirectory extends AgentDirectory {
    Optional<AgentDirectoryLease> acquire(
            AgentIdentity identity,
            AgentLocation location,
            String ownerSession,
            Duration leaseTtl
    );

    Optional<AgentDirectoryLease> renew(AgentDirectoryLease lease, Duration leaseTtl);

    Optional<AgentDirectoryLease> transfer(
            AgentDirectoryLease lease,
            AgentLocation nextLocation,
            String nextOwnerSession,
            Duration leaseTtl
    );

    boolean release(AgentDirectoryLease lease);

    Optional<AgentDirectoryLease> locateLease(AgentIdentity identity);
}
