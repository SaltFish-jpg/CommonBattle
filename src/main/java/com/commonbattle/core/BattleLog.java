package com.commonbattle.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only battle log.
 * Logs are intended for replay, client presentation, debugging, reconnect recovery, and sync verification.
 */
public final class BattleLog {
    private final List<BattleLogEntry> entries = new ArrayList<>();

    public void add(String type, String message) {
        entries.add(new BattleLogEntry(entries.size() + 1L, Instant.now(), type, message));
    }

    public List<BattleLogEntry> entries() {
        return List.copyOf(entries);
    }
}
