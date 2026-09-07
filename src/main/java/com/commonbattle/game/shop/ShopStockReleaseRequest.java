package com.commonbattle.game.shop;

import java.util.Objects;

/**
 * 释放全服库存请求。
 */
public record ShopStockReleaseRequest(String reservationId, String sku, int count) {
    public ShopStockReleaseRequest(String sku, int count) {
        this("", sku, count);
    }

    public ShopStockReleaseRequest {
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
