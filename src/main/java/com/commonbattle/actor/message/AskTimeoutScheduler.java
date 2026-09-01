package com.commonbattle.actor.message;

import java.time.Duration;

/**
 * ask 超时调度器。
 * 线上使用定时线程池，测试可替换为手动触发实现。
 */
public interface AskTimeoutScheduler extends AutoCloseable {
    ScheduledAskTimeout schedule(Duration timeout, Runnable action);

    @Override
    default void close() {
    }
}
