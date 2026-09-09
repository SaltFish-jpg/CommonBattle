package com.commonbattle.game.player;

/**
 * 玩家侧异步商店购买链路的只读观测视图。
 * Game 服启动流程注册该视图后，健康探针可以聚合 RPC 回调、超时晚到和最终结算指标。
 */
public interface AsyncShopPurchaseView {
    AsyncShopPurchaseStats stats();
}
