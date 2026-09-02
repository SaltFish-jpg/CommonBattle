package com.commonbattle.cluster.event;

import java.util.Set;

/**
 * 事件重放结果。
 * unavailableOwners 表示中心历史窗口已经覆盖，订阅方需要回源拉快照修补。
 */
public record EventReplayResult(int delivered, int unavailableOwners, Set<String> unavailableOwnerKeys) {
    public EventReplayResult() {
        this(0, 0, Set.of());
    }

    public EventReplayResult(int delivered, int unavailableOwners) {
        this(delivered, unavailableOwners, Set.of());
    }

    public EventReplayResult {
        if (delivered < 0) {
            throw new IllegalArgumentException("delivered must not be negative");
        }
        if (unavailableOwners < 0) {
            throw new IllegalArgumentException("unavailable owners must not be negative");
        }
        unavailableOwnerKeys = Set.copyOf(unavailableOwnerKeys);
        if (unavailableOwners < unavailableOwnerKeys.size()) {
            throw new IllegalArgumentException("unavailable owners must cover unavailable owner keys");
        }
    }
}
