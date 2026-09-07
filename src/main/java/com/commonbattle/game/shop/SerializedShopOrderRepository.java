package com.commonbattle.game.shop;

import com.commonbattle.persistence.AtomicBytesStore;

import java.util.Objects;
import java.util.Optional;

/**
 * 基于原子字节存储的商店订单仓储。
 * 生产环境可把 AtomicBytesStore 替换为 Redis SETNX 或 DB 唯一键写入，以保证订单幂等。
 */
public final class SerializedShopOrderRepository implements ShopOrderRepository {
    private static final String PREFIX = "shop:order:";

    private final AtomicBytesStore store;
    private final ShopPurchaseOrderSerializer serializer;

    public SerializedShopOrderRepository(AtomicBytesStore store, ShopPurchaseOrderSerializer serializer) {
        this.store = Objects.requireNonNull(store, "store");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public Optional<ShopPurchaseOrder> find(String orderId) {
        return store.load(key(orderId)).map(serializer::deserialize);
    }

    @Override
    public boolean saveIfAbsent(ShopPurchaseOrder order) {
        Objects.requireNonNull(order, "order");
        return store.putIfAbsent(key(order.orderId()), serializer.serialize(order));
    }

    private static String key(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        if (orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        return PREFIX + orderId;
    }
}
