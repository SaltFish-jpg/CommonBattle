package com.commonbattle.game.shop;

/**
 * 商店购买结果状态。
 */
public enum ShopPurchaseStatus {
    SUCCESS,
    UNKNOWN_ITEM,
    LIFETIME_LIMIT_REACHED,
    DAILY_LIMIT_REACHED,
    NOT_ENOUGH_CURRENCY,
    OUT_OF_STOCK,
    ORDER_CONFLICT
}
