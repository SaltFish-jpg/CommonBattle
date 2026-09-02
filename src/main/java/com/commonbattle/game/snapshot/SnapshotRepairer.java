package com.commonbattle.game.snapshot;

import java.util.Set;

/**
 * 批量快照修补入口。
 * 事件 replay 发现历史窗口缺口时，通过它按 ownerKey 回源拉取最新快照并刷新本地 cache。
 */
@FunctionalInterface
public interface SnapshotRepairer {
    SnapshotRepairReport repair(Set<String> ownerKeys);
}
