package com.commonbattle.game.bag;

import java.util.Objects;

/**
 * 背包物品配置。
 * 定义物品是否可堆叠、单格上限，以及后续玩法可读取的类型标签。
 */
public record ItemDefinition(String itemId, String type, int stackLimit) {
    public ItemDefinition {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(type, "type");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("Item id must not be blank");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("Item type must not be blank");
        }
        if (stackLimit <= 0) {
            throw new IllegalArgumentException("stackLimit must be positive");
        }
    }
}
