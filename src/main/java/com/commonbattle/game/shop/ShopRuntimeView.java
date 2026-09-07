package com.commonbattle.game.shop;

/**
 * 商店运行时只读观测视图。
 */
@FunctionalInterface
public interface ShopRuntimeView {
    ShopRuntimeStats stats();
}
