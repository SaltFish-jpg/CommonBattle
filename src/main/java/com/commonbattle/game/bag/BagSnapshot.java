package com.commonbattle.game.bag;

import java.util.Map;

/**
 * 玩家背包可持久化快照。
 */
public record BagSnapshot(Map<String, Integer> itemCounts) {
    public BagSnapshot {
        itemCounts = Map.copyOf(itemCounts);
    }
}
