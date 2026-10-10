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
        long currentPendingCommands,
        long reservedCallbacks,
        long outOfStockCallbacks,
        long rpcFailures,
        long lateCallbacks,
        long completedPurchases,
        long rejectedPurchases,
        long abandonedPendingCommands,
        long releasedReservations,
        long releaseFailures,
        long stockReservationCallbackGap,
        long compensationAttempts
) {
    public static AsyncShopPurchaseHealthStats empty() {
        return new AsyncShopPurchaseHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static AsyncShopPurchaseHealthStats from(int viewCount, AsyncShopPurchaseStats stats) {
        long callbackGap = Math.max(0, stats.stockReservations()
                - stats.reservedCallbacks()
                - stats.outOfStockCallbacks()
                - stats.rpcFailures()
                - stats.lateCallbacks()
                - stats.abandonedPendingCommands());
        long compensationAttempts = stats.releasedReservations() + stats.releaseFailures();
        return new AsyncShopPurchaseHealthStats(
                viewCount,
                stats.startedPurchases(),
                stats.immediatePurchases(),
                stats.stockReservations(),
                stats.currentPendingCommands(),
                stats.reservedCallbacks(),
                stats.outOfStockCallbacks(),
                stats.rpcFailures(),
                stats.lateCallbacks(),
                stats.completedPurchases(),
                stats.rejectedPurchases(),
                stats.abandonedPendingCommands(),
                stats.releasedReservations(),
                stats.releaseFailures(),
                callbackGap,
                compensationAttempts
        );
    }
}
