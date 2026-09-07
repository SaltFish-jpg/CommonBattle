package com.commonbattle.game.shop;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * 商店库存预占保留清理服务。
 * Center 服可定时调用它释放过期 reservation，避免 Game 服崩溃后全服库存被永久占用。
 */
public final class ShopStockReservationRetentionService implements ShopRuntimeView {
    private final ShopStockReservationRepository stocks;
    private final LongAdder runs = new LongAdder();
    private final LongAdder reapedReservations = new LongAdder();
    private final LongAdder failedRuns = new LongAdder();

    public ShopStockReservationRetentionService(ShopStockReservationRepository stocks) {
        this.stocks = Objects.requireNonNull(stocks, "stocks");
    }

    public int reap() {
        runs.increment();
        try {
            int reaped = stocks.reapExpiredReservations();
            reapedReservations.add(reaped);
            return reaped;
        } catch (RuntimeException e) {
            failedRuns.increment();
            throw e;
        }
    }

    @Override
    public ShopRuntimeStats stats() {
        return new ShopRuntimeStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                stocks.reservationCount(), runs.sum(), reapedReservations.sum(), failedRuns.sum());
    }
}
