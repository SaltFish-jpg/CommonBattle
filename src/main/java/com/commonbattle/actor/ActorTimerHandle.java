package com.commonbattle.actor;

/**
 * Actor 定时任务句柄。
 * 业务用它取消尚未触发或周期触发的定时消息。
 */
public interface ActorTimerHandle {
    boolean cancel();

    boolean isCancelled();
}
