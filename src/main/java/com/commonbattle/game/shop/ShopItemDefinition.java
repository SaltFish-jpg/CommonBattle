package com.commonbattle.game.shop;

import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.Reward;

import java.util.Objects;

/**
 * 商店商品配置。
 * lifetimeLimit/dailyLimit 为 0 表示不限购；globalStock 为 -1 表示无全服库存限制。
 */
public record ShopItemDefinition(
        String sku,
        ItemStack price,
        Reward reward,
        int lifetimeLimit,
        int dailyLimit,
        int globalStock
) {
    public static final int UNLIMITED_STOCK = -1;

    public ShopItemDefinition {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(reward, "reward");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (lifetimeLimit < 0) {
            throw new IllegalArgumentException("lifetimeLimit must not be negative");
        }
        if (dailyLimit < 0) {
            throw new IllegalArgumentException("dailyLimit must not be negative");
        }
        if (globalStock < UNLIMITED_STOCK) {
            throw new IllegalArgumentException("globalStock must be -1 or positive");
        }
    }

    public boolean limitedStock() {
        return globalStock >= 0;
    }
}
