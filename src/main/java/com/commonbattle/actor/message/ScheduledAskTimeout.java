package com.commonbattle.actor.message;

/**
 * 已登记的 ask 超时任务。
 */
@FunctionalInterface
public interface ScheduledAskTimeout {
    void cancel();
}
