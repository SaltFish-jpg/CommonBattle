package com.commonbattle.actor.agent.migration;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * 单个热点 Actor 的迁移计划执行结果。
 */
public record ActorHotspotMigrationResult(
        String actorId,
        Optional<AgentIdentity> identity,
        Optional<AgentLocation> target,
        ActorHotspotMigrationStatus status,
        String reason
) {
    public ActorHotspotMigrationResult {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        identity = identity == null ? Optional.empty() : identity;
        target = target == null ? Optional.empty() : target;
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
    }

    public static ActorHotspotMigrationResult of(
            String actorId,
            Optional<AgentIdentity> identity,
            Optional<AgentLocation> target,
            ActorHotspotMigrationStatus status,
            String reason
    ) {
        return new ActorHotspotMigrationResult(actorId, identity, target, status, reason);
    }
}
