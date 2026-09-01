package com.commonbattle.game.event;

/**
 * 订阅方处理版本事件前的判定结果。
 */
public enum SubscriptionDecision {
    APPLY,
    DUPLICATE_OR_OLD,
    GAP
}
