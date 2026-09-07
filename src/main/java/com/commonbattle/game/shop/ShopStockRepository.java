package com.commonbattle.game.shop;

/**
 * 商店全服库存存储。
 * 单玩家购买在玩家 Agent 内串行，全服库存需要独立 CAS，生产环境可替换为 Redis 或 DB 条件更新。
 */
public interface ShopStockRepository {
    boolean reserve(String sku, int count);

    default boolean reserve(String reservationId, String sku, int count) {
        return reserve(sku, count);
    }

    void release(String sku, int count);

    default void release(String reservationId, String sku, int count) {
        release(sku, count);
    }

    int remaining(String sku);

    static ShopStockRepository unlimited() {
        return Unlimited.INSTANCE;
    }

    enum Unlimited implements ShopStockRepository {
        INSTANCE;

        @Override
        public boolean reserve(String sku, int count) {
            return true;
        }

        @Override
        public boolean reserve(String reservationId, String sku, int count) {
            return true;
        }

        @Override
        public void release(String sku, int count) {
        }

        @Override
        public void release(String reservationId, String sku, int count) {
        }

        @Override
        public int remaining(String sku) {
            return Integer.MAX_VALUE;
        }
    }
}
