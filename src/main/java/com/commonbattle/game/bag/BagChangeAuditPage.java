package com.commonbattle.game.bag;

import java.util.List;

/**
 * 背包变更流水分页结果。
 */
public record BagChangeAuditPage(
        List<BagChangeAuditRecord> entries,
        int matched,
        int offset,
        int limit
) {
    public BagChangeAuditPage {
        entries = List.copyOf(entries);
        if (matched < 0) {
            throw new IllegalArgumentException("matched must not be negative");
        }
    }
}
