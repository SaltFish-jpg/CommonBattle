package com.commonbattle.game.session;

import java.util.List;

/**
 * 玩家命令审计分页结果。
 */
public record PlayerCommandAuditPage(
        List<PlayerCommandAuditRecord> entries,
        int matched,
        int offset,
        int limit
) {
    public PlayerCommandAuditPage {
        entries = List.copyOf(entries);
        if (matched < 0) {
            throw new IllegalArgumentException("matched must not be negative");
        }
    }
}
