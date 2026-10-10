package com.commonbattle.observability;

import com.commonbattle.game.event.EventGatedCommandAuditStats;

/**
 * 事件门控命令审计聚合健康统计。
 */
public record EventGatedCommandAuditHealthStats(
        int auditCount,
        int retained,
        long recorded,
        long submitted,
        long completed,
        long failed,
        long dropped
) {
    public EventGatedCommandAuditHealthStats {
        if (auditCount < 0 || retained < 0 || recorded < 0 || submitted < 0
                || completed < 0 || failed < 0 || dropped < 0) {
            throw new IllegalArgumentException("event gated command audit health stats must not be negative");
        }
    }

    public static EventGatedCommandAuditHealthStats empty() {
        return new EventGatedCommandAuditHealthStats(0, 0, 0, 0, 0, 0, 0);
    }

    public static EventGatedCommandAuditHealthStats from(int auditCount, EventGatedCommandAuditStats stats) {
        return new EventGatedCommandAuditHealthStats(
                auditCount,
                stats.retained(),
                stats.recorded(),
                stats.submitted(),
                stats.completed(),
                stats.failed(),
                stats.dropped()
        );
    }

    public EventGatedCommandAuditHealthStats plus(EventGatedCommandAuditHealthStats other) {
        return new EventGatedCommandAuditHealthStats(
                auditCount + other.auditCount,
                retained + other.retained,
                recorded + other.recorded,
                submitted + other.submitted,
                completed + other.completed,
                failed + other.failed,
                dropped + other.dropped
        );
    }
}
