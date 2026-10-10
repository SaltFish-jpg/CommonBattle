package com.commonbattle.game.event;

/**
 * 事件门控命令审计统计。
 */
public record EventGatedCommandAuditStats(
        int retained,
        long recorded,
        long submitted,
        long completed,
        long failed,
        long dropped
) {
    public EventGatedCommandAuditStats {
        if (retained < 0 || recorded < 0 || submitted < 0 || completed < 0 || failed < 0 || dropped < 0) {
            throw new IllegalArgumentException("event gated command audit stats must not be negative");
        }
    }
}
