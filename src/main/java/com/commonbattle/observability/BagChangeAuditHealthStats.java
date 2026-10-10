package com.commonbattle.observability;

import com.commonbattle.game.bag.BagChangeAuditStats;

/**
 * 背包变更流水聚合健康统计。
 */
public record BagChangeAuditHealthStats(
        int auditCount,
        int retained,
        long recorded,
        long increased,
        long decreased,
        long unchanged,
        long dropped
) {
    public BagChangeAuditHealthStats {
        if (auditCount < 0 || retained < 0 || recorded < 0 || increased < 0
                || decreased < 0 || unchanged < 0 || dropped < 0) {
            throw new IllegalArgumentException("bag change audit health stats must not be negative");
        }
    }

    public static BagChangeAuditHealthStats empty() {
        return new BagChangeAuditHealthStats(0, 0, 0, 0, 0, 0, 0);
    }

    public static BagChangeAuditHealthStats from(int auditCount, BagChangeAuditStats stats) {
        return new BagChangeAuditHealthStats(
                auditCount,
                stats.retained(),
                stats.recorded(),
                stats.increased(),
                stats.decreased(),
                stats.unchanged(),
                stats.dropped()
        );
    }

    public BagChangeAuditHealthStats plus(BagChangeAuditHealthStats other) {
        return new BagChangeAuditHealthStats(
                auditCount + other.auditCount,
                retained + other.retained,
                recorded + other.recorded,
                increased + other.increased,
                decreased + other.decreased,
                unchanged + other.unchanged,
                dropped + other.dropped
        );
    }
}
