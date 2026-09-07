package com.commonbattle.game.shop;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存商店订单流水。
 * 适合单服样例和测试；多进程部署时应使用带唯一键约束的持久化实现。
 */
public final class InMemoryShopOrderRepository implements ShopOrderRepository {
    private final ConcurrentHashMap<String, ShopPurchaseOrder> orders = new ConcurrentHashMap<>();

    @Override
    public Optional<ShopPurchaseOrder> find(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public boolean saveIfAbsent(ShopPurchaseOrder order) {
        Objects.requireNonNull(order, "order");
        return orders.putIfAbsent(order.orderId(), order) == null;
    }

    public int size() {
        return orders.size();
    }
}
