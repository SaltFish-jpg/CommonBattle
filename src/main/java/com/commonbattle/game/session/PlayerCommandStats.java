package com.commonbattle.game.session;

import java.util.Map;

/**
 * 玩家命令入口统计快照。
 */
public record PlayerCommandStats(Map<PlayerCommandStatus, Long> counts) {
    public PlayerCommandStats {
        counts = Map.copyOf(counts);
    }

    public static PlayerCommandStats empty() {
        return new PlayerCommandStats(Map.of());
    }

    public long count(PlayerCommandStatus status) {
        return counts.getOrDefault(status, 0L);
    }

    public PlayerCommandStats plus(PlayerCommandStats other) {
        java.util.EnumMap<PlayerCommandStatus, Long> merged = new java.util.EnumMap<>(PlayerCommandStatus.class);
        counts.forEach(merged::put);
        other.counts.forEach((status, value) -> merged.merge(status, value, Long::sum));
        return new PlayerCommandStats(merged);
    }
}
