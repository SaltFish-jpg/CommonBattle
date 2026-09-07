package com.commonbattle.game.shop;

import java.util.Objects;

/**
 * 查询全服库存请求。
 */
public record ShopStockRemainingRequest(String sku) {
    public ShopStockRemainingRequest {
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
    }
}
