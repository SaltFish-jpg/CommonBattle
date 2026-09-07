package com.commonbattle.game.shop;

import java.util.Map;

/**
 * 玩家商店购买状态快照。
 */
public record PlayerShopSnapshot(
        Map<String, Integer> lifetimePurchases,
        Map<String, Integer> dailyPurchases
) {
    public PlayerShopSnapshot {
        lifetimePurchases = Map.copyOf(lifetimePurchases);
        dailyPurchases = Map.copyOf(dailyPurchases);
    }

    public static PlayerShopSnapshot empty() {
        return new PlayerShopSnapshot(Map.of(), Map.of());
    }
}
