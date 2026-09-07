package com.commonbattle.game.shop;

/**
 * 预占全服库存结果。
 */
public record ShopStockReserveResponse(boolean reserved, int remaining) {
}
