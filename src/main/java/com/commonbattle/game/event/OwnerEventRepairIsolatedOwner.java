package com.commonbattle.game.event;

/**
 * owner 投影修复隔离队列中的 owner 只读信息。
 */
public record OwnerEventRepairIsolatedOwner(
        String ownerKey,
        int priority,
        long remainingMillis,
        int failureCount,
        String lastFailureMessage
) {
    public OwnerEventRepairIsolatedOwner(String ownerKey, int priority, long remainingMillis) {
        this(ownerKey, priority, remainingMillis, 0, "");
    }

    public OwnerEventRepairIsolatedOwner {
        lastFailureMessage = lastFailureMessage == null ? "" : lastFailureMessage;
    }
}
