package com.commonbattle.actor.backpressure;

import java.time.Instant;
import java.util.Objects;

/**
 * 单个 Actor 的热点治理人工覆盖记录。
 * 覆盖记录带过期时间，避免人工操作长期遗留在线上入口。
 */
public record ActorHotspotOverride(
        String actorId,
        ActorHotspotOverrideMode mode,
        Instant expiresAt,
        String reason
) {
    public ActorHotspotOverride {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(expiresAt, "expiresAt");
        reason = reason == null ? "" : reason;
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
