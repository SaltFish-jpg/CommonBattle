package com.commonbattle.actor.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * Agent owner 的限时所有权凭证。
 * fencingToken 在每次过期接管后单调递增，持久化写入方可用它拒绝旧 owner。
 */
public record AgentDirectoryLease(
        AgentIdentity identity,
        AgentLocation location,
        String ownerSession,
        long fencingToken,
        Instant expiresAt
) {
    public AgentDirectoryLease {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(ownerSession, "ownerSession");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (ownerSession.isBlank()) {
            throw new IllegalArgumentException("ownerSession must not be blank");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    public boolean validAt(Instant now) {
        return expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }
}
