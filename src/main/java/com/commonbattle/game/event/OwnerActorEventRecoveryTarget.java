package com.commonbattle.game.event;

/**
 * owner 关注型事件订阅恢复目标。
 * 由持有 owner 订阅状态的组件实现，恢复调度器只负责定时触发，不关心具体 topic。
 */
@FunctionalInterface
public interface OwnerActorEventRecoveryTarget {
    void recoverOwnerSubscriptions();
}
