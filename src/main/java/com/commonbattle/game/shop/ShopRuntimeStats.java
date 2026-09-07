package com.commonbattle.game.shop;

/**
 * 商店运行时统计。
 */
public record ShopRuntimeStats(
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
    public ShopRuntimeStats(
            long purchaseRequests,
            long successfulPurchases,
            long idempotentReplays,
            long unknownItems,
            long lifetimeLimitRejected,
            long dailyLimitRejected,
            long notEnoughCurrency,
            long outOfStock,
            long orderConflicts,
            long recordedOrders
    ) {
        this(purchaseRequests, successfulPurchases, idempotentReplays, unknownItems, lifetimeLimitRejected,
                dailyLimitRejected, notEnoughCurrency, outOfStock, orderConflicts, recordedOrders, 0, 0, 0, 0);
    }

    public static ShopRuntimeStats empty() {
        return new ShopRuntimeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public ShopRuntimeStats plus(ShopRuntimeStats other) {
        return new ShopRuntimeStats(
                purchaseRequests + other.purchaseRequests,
                successfulPurchases + other.successfulPurchases,
                idempotentReplays + other.idempotentReplays,
                unknownItems + other.unknownItems,
                lifetimeLimitRejected + other.lifetimeLimitRejected,
                dailyLimitRejected + other.dailyLimitRejected,
                notEnoughCurrency + other.notEnoughCurrency,
                outOfStock + other.outOfStock,
                orderConflicts + other.orderConflicts,
                recordedOrders + other.recordedOrders,
                activeReservations + other.activeReservations,
                reservationReapRuns + other.reservationReapRuns,
                reapedReservations + other.reapedReservations,
                reservationReapFailures + other.reservationReapFailures
        );
    }
}
