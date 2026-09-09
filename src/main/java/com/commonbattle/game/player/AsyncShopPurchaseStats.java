package com.commonbattle.game.player;

/**
 * 玩家侧异步商店购买链路统计。
 * 它统计的是玩家 Actor 发起库存 RPC 到回调回邮后的端到端执行，不替代库存服务自身的商店统计。
 */
public record AsyncShopPurchaseStats(
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
    public static AsyncShopPurchaseStats empty() {
        return new AsyncShopPurchaseStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public AsyncShopPurchaseStats plus(AsyncShopPurchaseStats other) {
        return new AsyncShopPurchaseStats(
                startedPurchases + other.startedPurchases,
                immediatePurchases + other.immediatePurchases,
                stockReservations + other.stockReservations,
                reservedCallbacks + other.reservedCallbacks,
                outOfStockCallbacks + other.outOfStockCallbacks,
                rpcFailures + other.rpcFailures,
                lateCallbacks + other.lateCallbacks,
                completedPurchases + other.completedPurchases,
                rejectedPurchases + other.rejectedPurchases,
                releasedReservations + other.releasedReservations,
                releaseFailures + other.releaseFailures
        );
    }
}
