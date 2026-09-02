package com.commonbattle.cluster.event;

import java.util.Map;
import java.util.Set;

/**
 * 版本事件订阅方的本地游标读取入口。
 * 实现方通常从本进程 cache 或持久化 checkpoint 返回 ownerKey -> 已应用 revision。
 */
@FunctionalInterface
public interface SubscriptionCursor {
    Map<String, Long> knownRevisions();

    default Set<String> ownerKeys() {
        return knownRevisions().keySet();
    }

    static SubscriptionCursor empty() {
        return Map::of;
    }
}
