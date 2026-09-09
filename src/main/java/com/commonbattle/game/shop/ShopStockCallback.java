package com.commonbattle.game.shop;

/**
 * 商店库存异步回调。
 * RPC、Netty 或消息总线线程只能调用该回调交付结果，业务状态修改需要再投递回 owner Actor。
 */
public interface ShopStockCallback<T> {
    void success(T response);

    void failure(Throwable error);
}
