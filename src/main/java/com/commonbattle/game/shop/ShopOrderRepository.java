package com.commonbattle.game.shop;

import java.util.Optional;

/**
 * 商店订单流水存储。
 * 玩家 Agent 内串行调用可使用内存实现；生产环境可替换为 DB 唯一键或 Redis SETNX。
 */
public interface ShopOrderRepository {
    Optional<ShopPurchaseOrder> find(String orderId);

    boolean saveIfAbsent(ShopPurchaseOrder order);

    static ShopOrderRepository none() {
        return Noop.INSTANCE;
    }

    enum Noop implements ShopOrderRepository {
        INSTANCE;

        @Override
        public Optional<ShopPurchaseOrder> find(String orderId) {
            return Optional.empty();
        }

        @Override
        public boolean saveIfAbsent(ShopPurchaseOrder order) {
            return false;
        }
    }
}
