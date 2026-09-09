package com.commonbattle.observability;

import com.commonbattle.game.player.AsyncShopPurchaseStats;

/**
 * 玩家侧异步商店购买链路的聚合健康统计。
 */
public record AsyncShopPurchaseHealthStats(
        int viewCount,
        long startedPurchases,
        long immediatePurchases,
        long stockReservations,
        long reservedCallbacks,
        long outOfStockCallbacks,
        long rpcFailures,
        long lateCallbacks,
        long completedPurchases,
        long rejectedPurchases,
        long releasedReservations,
        long releaseFailures
) {
    public static AsyncShopPurchaseHealthStats empty() {
        return new AsyncShopPurchaseHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static AsyncShopPurchaseHealthStats from(int viewCount, AsyncShopPurchaseStats stats) {
        return new AsyncShopPurchaseHealthStats(
                viewCount,
                stats.startedPurchases(),
                stats.immediatePurchases(),
                stats.stockReservations(),
                stats.reservedCallbacks(),
                stats.outOfStockCallbacks(),
                stats.rpcFailures(),
                stats.lateCallbacks(),
                stats.completedPurchases(),
                stats.rejectedPurchases(),
                stats.releasedReservations(),
                stats.releaseFailures()
        );
    }
}
