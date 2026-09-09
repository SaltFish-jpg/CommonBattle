package com.commonbattle.game.shop;

/**
 * 商店全服库存异步客户端。
 * Game 服玩家 Actor 发起预占后立即让出线程，底层回包再通过适配器回投玩家 mailbox。
 */
public interface ShopStockAsyncClient {
    void reserve(String reservationId, String sku, int count, ShopStockCallback<ShopStockReserveResponse> callback);

    void release(String reservationId, String sku, int count, ShopStockCallback<ShopStockReleaseResponse> callback);

    void remaining(String sku, ShopStockCallback<ShopStockRemainingResponse> callback);
}
