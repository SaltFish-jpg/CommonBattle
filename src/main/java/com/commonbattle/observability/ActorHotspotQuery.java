package com.commonbattle.observability;

/**
 * Actor 热点治理候选查询条件。
 */
public record ActorHotspotQuery(
        ActorHotspotAction action,
        String group,
        String actorIdPrefix,
        int offset,
        int limit
) {
    public ActorHotspotQuery {
        group = group == null || group.isBlank() ? null : group;
        actorIdPrefix = actorIdPrefix == null || actorIdPrefix.isBlank() ? null : actorIdPrefix;
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("actor hotspot query values must be valid");
        }
    }
}
