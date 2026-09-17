package com.commonbattle.game.event;

/**
 * owner 事件投影修复调度器只读观测视图。
 */
@FunctionalInterface
public interface OwnerEventRepairSchedulerView {
    OwnerEventRepairSchedulerStats repairSchedulerStats();
}
