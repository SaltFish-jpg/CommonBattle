package com.commonbattle.observability;

import java.util.List;
import java.util.Objects;

/**
 * Actor 热点治理候选分页结果。
 */
public record ActorHotspotPage(
        List<ActorHotspotCandidate> entries,
        int matched,
        int offset,
        int limit
) {
    public ActorHotspotPage {
        Objects.requireNonNull(entries, "entries");
        if (matched < 0 || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("actor hotspot page values must be valid");
        }
        entries = List.copyOf(entries);
    }
}
