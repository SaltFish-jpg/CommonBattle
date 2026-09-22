package com.commonbattle.observability;

import com.commonbattle.actor.ActorMailboxStats;

import java.util.List;
import java.util.Objects;

/**
 * Actor 热邮箱分页结果。
 */
public record ActorMailboxPage(
        List<ActorMailboxStats> entries,
        int matched,
        int offset,
        int limit
) {
    public ActorMailboxPage {
        Objects.requireNonNull(entries, "entries");
        if (matched < 0 || offset < 0 || limit < 1) {
            throw new IllegalArgumentException("mailbox page values must be valid");
        }
        entries = List.copyOf(entries);
    }
}
