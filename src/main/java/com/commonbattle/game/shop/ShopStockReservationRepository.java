package com.commonbattle.game.shop;

/**
 * 支持 reservation 生命周期管理的商店库存仓储。
 * Center 服清理器只依赖该接口，不关心底层是内存、Redis 还是 DB。
 */
public interface ShopStockReservationRepository extends ShopStockRepository {
    int reservationCount();

    int reapExpiredReservations();
}
