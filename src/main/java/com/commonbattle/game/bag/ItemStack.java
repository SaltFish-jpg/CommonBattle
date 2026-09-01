package com.commonbattle.game.bag;

/**
 * 背包中的一组同类物品。
 */
public record ItemStack(String itemId, int count) {
    public ItemStack {
        if (count <= 0) {
            throw new IllegalArgumentException("Item count must be positive");
        }
    }
}
