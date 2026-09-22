package com.commonbattle.observability;

import java.util.List;
import java.util.Objects;

/**
 * Actor incident 分页查询结果。
 */
public record ActorIncidentPage(
        List<ActorIncidentRecord> entries,
        int matched,
        int offset,
        int limit
) {
    public ActorIncidentPage {
        Objects.requireNonNull(entries, "entries");
        if (matched < 0 || offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("actor incident page values are invalid");
        }
        entries = List.copyOf(entries);
    }
}
