package com.commonbattle.observability;

import java.util.Map;
import java.util.Objects;

/**
 * 玩家命令审计健康统计。
 * configVersionCounts 用于观察灰度或回滚期间不同配置版本承载的命令量。
 */
public record PlayerCommandAuditHealthStats(
        long total,
        long retained,
        long dropped,
        long executed,
        long failed,
        long rejected,
        long routedRemote,
        long maxElapsedMillis,
        Map<Long, Long> configVersionCounts
) {
    public PlayerCommandAuditHealthStats {
        Objects.requireNonNull(configVersionCounts, "configVersionCounts");
        if (total < 0 || retained < 0 || dropped < 0 || executed < 0 || failed < 0
                || rejected < 0 || routedRemote < 0 || maxElapsedMillis < 0) {
            throw new IllegalArgumentException("audit stats must not be negative");
        }
        configVersionCounts = Map.copyOf(configVersionCounts);
    }

    public static PlayerCommandAuditHealthStats empty() {
        return new PlayerCommandAuditHealthStats(0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }
}
