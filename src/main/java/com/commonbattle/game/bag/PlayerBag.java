package com.commonbattle.game.bag;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家背包状态。
 * 该对象应只在玩家 Agent 邮箱内修改，跨线程入口需要先投递消息。
 */
public final class PlayerBag {
    private final Map<String, Integer> counts = new HashMap<>();

    public int count(String itemId) {
        return counts.getOrDefault(itemId, 0);
    }

    public boolean has(String itemId, int count) {
        return count(itemId) >= count;
    }

    public BagSnapshot snapshot() {
        return new BagSnapshot(counts);
    }

    public void restore(BagSnapshot snapshot) {
        counts.clear();
        snapshot.itemCounts().forEach((itemId, count) -> {
            if (count > 0) {
                counts.put(itemId, count);
            }
        });
    }

    void add(String itemId, int count) {
        counts.merge(itemId, count, Integer::sum);
    }

    void remove(String itemId, int count) {
        int current = count(itemId);
        if (current < count) {
            throw new IllegalStateException("Not enough item " + itemId);
        }
        int next = current - count;
        if (next == 0) {
            counts.remove(itemId);
        } else {
            counts.put(itemId, next);
        }
    }
}
