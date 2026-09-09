package com.commonbattle.cluster.registry;

import com.commonbattle.cluster.RegistryEvent;

import java.util.List;

/**
 * 注册目录增量重放结果。
 * compacted=true 表示中心历史不足，订阅端必须重新拉全量快照修复。
 */
public record RegistryReplayResponse(
        List<RegistryEvent> events,
        long currentVersion,
        boolean compacted,
        long minReplayVersion
) {
    public RegistryReplayResponse(List<RegistryEvent> events, long currentVersion, boolean compacted) {
        this(events, currentVersion, compacted, 0);
    }

    public RegistryReplayResponse {
        events = List.copyOf(events);
        if (currentVersion < 0 || minReplayVersion < 0) {
            throw new IllegalArgumentException("registry replay versions must not be negative");
        }
    }
}
