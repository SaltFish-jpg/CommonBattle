package com.commonbattle.game.event;

import java.util.Collection;

/**
 * owner 投影修复的人工重放控制口。
 * 运维入口通过 target 精确选择某个 topic 的修复队列，避免多订阅场景下把 owner 投错队列。
 */
public interface OwnerEventRepairRequestAdmin {
    String target();

    String topic();

    void requestRepairOwners(Collection<String> ownerKeys);

    void requestRepairOwners(Collection<String> ownerKeys, int priority);

    default void requestRepairOwner(String ownerKey) {
        requestRepairOwners(java.util.Set.of(ownerKey));
    }

    default void requestRepairOwner(String ownerKey, int priority) {
        requestRepairOwners(java.util.Set.of(ownerKey), priority);
    }
}
