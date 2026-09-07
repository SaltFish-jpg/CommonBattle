package com.commonbattle.observability;

import com.commonbattle.game.shop.ShopRuntimeStats;

/**
 * 商店运行时聚合健康统计。
 */
public record ShopRuntimeHealthStats(
        int runtimeCount,
        long purchaseRequests,
        long successfulPurchases,
        long idempotentReplays,
        long unknownItems,
        long lifetimeLimitRejected,
        long dailyLimitRejected,
        long notEnoughCurrency,
        long outOfStock,
        long orderConflicts,
        long recordedOrders,
        long activeReservations,
        long reservationReapRuns,
        long reapedReservations,
        long reservationReapFailures
) {
    public static ShopRuntimeHealthStats empty() {
        return new ShopRuntimeHealthStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static ShopRuntimeHealthStats from(int runtimeCount, ShopRuntimeStats stats) {
        return new ShopRuntimeHealthStats(
                runtimeCount,
                stats.purchaseRequests(),
                stats.successfulPurchases(),
                stats.idempotentReplays(),
                stats.unknownItems(),
                stats.lifetimeLimitRejected(),
                stats.dailyLimitRejected(),
                stats.notEnoughCurrency(),
                stats.outOfStock(),
                stats.orderConflicts(),
                stats.recordedOrders(),
                stats.activeReservations(),
                stats.reservationReapRuns(),
                stats.reapedReservations(),
                stats.reservationReapFailures()
        );
    }
}
