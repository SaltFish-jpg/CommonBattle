package com.commonbattle.game.shop;

import java.util.Objects;

/**
 * 预占全服库存请求。
 */
public record ShopStockReserveRequest(String reservationId, String sku, int count) {
    public ShopStockReserveRequest(String sku, int count) {
        this("", sku, count);
    }

    public ShopStockReserveRequest {
        reservationId = Objects.requireNonNullElse(reservationId, "");
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
