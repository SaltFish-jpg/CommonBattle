package com.commonbattle.game.shop;

/**
 * 商店购买订单序列化器。
 * 持久化仓储通过它稳定保存成功订单，支持跨进程重启后的幂等回放。
 */
public interface ShopPurchaseOrderSerializer {
    byte[] serialize(ShopPurchaseOrder order);

    ShopPurchaseOrder deserialize(byte[] bytes);
}
