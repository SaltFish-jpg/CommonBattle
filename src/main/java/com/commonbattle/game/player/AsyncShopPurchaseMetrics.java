package com.commonbattle.game.player;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家侧异步商店购买链路指标累加器。
 * 多个玩家 Actor 可共享同一个实例，回调线程和玩家邮箱线程只做原子累加，不参与业务同步。
 */
public final class AsyncShopPurchaseMetrics implements AsyncShopPurchaseView {
    private final AtomicLong startedPurchases = new AtomicLong();
    private final AtomicLong immediatePurchases = new AtomicLong();
    private final AtomicLong stockReservations = new AtomicLong();
    private final AtomicLong reservedCallbacks = new AtomicLong();
    private final AtomicLong outOfStockCallbacks = new AtomicLong();
    private final AtomicLong rpcFailures = new AtomicLong();
    private final AtomicLong lateCallbacks = new AtomicLong();
    private final AtomicLong completedPurchases = new AtomicLong();
    private final AtomicLong rejectedPurchases = new AtomicLong();
    private final AtomicLong releasedReservations = new AtomicLong();
    private final AtomicLong releaseFailures = new AtomicLong();

    void startedPurchase() {
        startedPurchases.incrementAndGet();
    }

    void immediatePurchase() {
        immediatePurchases.incrementAndGet();
    }

    void stockReservation() {
        stockReservations.incrementAndGet();
    }

    void reservedCallback() {
        reservedCallbacks.incrementAndGet();
    }

    void outOfStockCallback() {
        outOfStockCallbacks.incrementAndGet();
    }

    void rpcFailure() {
        rpcFailures.incrementAndGet();
    }

    void lateCallback() {
        lateCallbacks.incrementAndGet();
    }

    void completedPurchase() {
        completedPurchases.incrementAndGet();
    }

    void rejectedPurchase() {
        rejectedPurchases.incrementAndGet();
    }

    void releasedReservation() {
        releasedReservations.incrementAndGet();
    }

    void releaseFailure() {
        releaseFailures.incrementAndGet();
    }

    @Override
    public AsyncShopPurchaseStats stats() {
        return new AsyncShopPurchaseStats(
                startedPurchases.get(),
                immediatePurchases.get(),
                stockReservations.get(),
                reservedCallbacks.get(),
                outOfStockCallbacks.get(),
                rpcFailures.get(),
                lateCallbacks.get(),
                completedPurchases.get(),
                rejectedPurchases.get(),
                releasedReservations.get(),
                releaseFailures.get()
        );
    }
}
