package com.commonbattle.game.scene;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Scene 内玩家领域投影只读视图。
 * 它承载从玩家 owner 事件或快照重建出的派生数据，供场景、聊天等服务做低延迟判断。
 */
public record ScenePlayerDomainView(
        long playerId,
        long revision,
        boolean stale,
        int totalStageClears,
        Map<String, Integer> bagItemCounts
) {
    public ScenePlayerDomainView {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (totalStageClears < 0) {
            throw new IllegalArgumentException("totalStageClears must not be negative");
        }
        bagItemCounts = Map.copyOf(Objects.requireNonNull(bagItemCounts, "bagItemCounts"));
    }

    public boolean freshAtLeast(long minimumRevision) {
        return !stale && revision >= minimumRevision;
    }

    public OptionalInt bagCount(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        Integer count = bagItemCounts.get(itemId);
        return count == null ? OptionalInt.empty() : OptionalInt.of(count);
    }
}
