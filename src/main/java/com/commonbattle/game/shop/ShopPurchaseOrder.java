package com.commonbattle.game.shop;

import java.time.Instant;
import java.util.Objects;

/**
 * 商店购买成功订单流水。
 * 用于购买单号幂等回放；失败购买不写入订单，允许玩家修正条件后重新发起。
 */
public record ShopPurchaseOrder(
        String orderId,
        String sku,
        int quantity,
        ShopPurchaseResult result,
        Instant createdAt
) {
    public ShopPurchaseOrder {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(createdAt, "createdAt");
        if (orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!result.success()) {
            throw new IllegalArgumentException("only successful purchase can be recorded");
        }
    }

    public boolean matches(String requestedSku, int requestedQuantity) {
        return sku.equals(requestedSku) && quantity == requestedQuantity;
    }
}
