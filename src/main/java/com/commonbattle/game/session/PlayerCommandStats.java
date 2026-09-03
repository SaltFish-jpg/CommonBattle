package com.commonbattle.game.session;

import java.util.Map;

/**
 * 玩家命令入口统计快照。
 */
public record PlayerCommandStats(
        Map<PlayerCommandStatus, Long> counts,
        int acceptingDispatchers,
        int drainingDispatchers
) {
    public PlayerCommandStats {
        counts = Map.copyOf(counts);
    }

    public PlayerCommandStats(Map<PlayerCommandStatus, Long> counts) {
        this(counts, 0, 0);
    }

    public static PlayerCommandStats empty() {
        return new PlayerCommandStats(Map.of(), 0, 0);
    }

    public long count(PlayerCommandStatus status) {
        return counts.getOrDefault(status, 0L);
    }

    public PlayerCommandStats plus(PlayerCommandStats other) {
        java.util.EnumMap<PlayerCommandStatus, Long> merged = new java.util.EnumMap<>(PlayerCommandStatus.class);
        counts.forEach(merged::put);
        other.counts.forEach((status, value) -> merged.merge(status, value, Long::sum));
        return new PlayerCommandStats(
                merged,
                acceptingDispatchers + other.acceptingDispatchers,
                drainingDispatchers + other.drainingDispatchers
        );
    }
}
