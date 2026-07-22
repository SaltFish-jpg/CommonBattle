package com.commonbattle.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 只追加的战斗日志。
 * 日志可用于回放、客户端展示、调试、断线恢复和同步校验。
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
