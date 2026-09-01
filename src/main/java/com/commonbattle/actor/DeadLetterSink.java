package com.commonbattle.actor;

/**
 * 死信接收器。
 * 线上通常接日志、监控或降级队列，测试可接内存列表。
 */
@FunctionalInterface
public interface DeadLetterSink {
    void accept(DeadLetter deadLetter);

    static DeadLetterSink ignore() {
        return ignored -> {
        };
    }
}
