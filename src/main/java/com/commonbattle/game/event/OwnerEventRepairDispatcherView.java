package com.commonbattle.game.event;

/**
 * owner 事件投影共享修复分发器只读观测视图。
 */
@FunctionalInterface
public interface OwnerEventRepairDispatcherView {
    OwnerEventRepairDispatcherStats repairDispatcherStats();
}
