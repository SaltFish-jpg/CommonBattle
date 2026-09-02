package com.commonbattle.game.session;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class PlayerCommandMetrics {
    private final Map<PlayerCommandStatus, LongAdder> counts = new EnumMap<>(PlayerCommandStatus.class);

    PlayerCommandMetrics() {
        for (PlayerCommandStatus status : PlayerCommandStatus.values()) {
            counts.put(status, new LongAdder());
        }
    }

    void record(PlayerCommandStatus status) {
        counts.get(status).increment();
    }

    PlayerCommandStats snapshot() {
        EnumMap<PlayerCommandStatus, Long> snapshot = new EnumMap<>(PlayerCommandStatus.class);
        counts.forEach((status, count) -> snapshot.put(status, count.sum()));
        return new PlayerCommandStats(snapshot);
    }
}
