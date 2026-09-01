package com.commonbattle.game.event;

import java.util.HashMap;
import java.util.Map;

/**
 * 订阅检查点。
 * 每个订阅者按 owner 记录最新 revision，用来保障事件处理幂等，并在跳号时标记快照不可信。
 */
public final class SubscriptionCheckpoint {
    private final Map<String, Long> revisions = new HashMap<>();

    public SubscriptionDecision inspect(VersionedEvent event) {
        long current = revisions.getOrDefault(event.ownerKey(), 0L);
        if (event.revision() <= current) {
            return SubscriptionDecision.DUPLICATE_OR_OLD;
        }
        if (event.revision() != current + 1) {
            return SubscriptionDecision.GAP;
        }
        return SubscriptionDecision.APPLY;
    }

    public void markApplied(VersionedEvent event) {
        revisions.put(event.ownerKey(), event.revision());
    }

    public long revisionOf(String ownerKey) {
        return revisions.getOrDefault(ownerKey, 0L);
    }
}
