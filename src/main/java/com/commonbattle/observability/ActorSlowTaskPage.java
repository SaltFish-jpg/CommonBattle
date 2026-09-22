package com.commonbattle.observability;

import java.util.List;
import java.util.Objects;

/**
 * Actor 慢任务分页结果。
 */
public record ActorSlowTaskPage(
        List<ActorSlowTaskRecord> entries,
        int matched,
        int offset,
        int limit
) {
    public ActorSlowTaskPage {
        Objects.requireNonNull(entries, "entries");
        if (matched < 0 || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("slow task page values must be valid");
        }
        entries = List.copyOf(entries);
    }
}
