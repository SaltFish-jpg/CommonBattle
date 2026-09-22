package com.commonbattle.actor;

/**
 * Actor 慢任务接收器。
 * 实现方通常把慢任务写入内存窗口、日志系统或告警管道，不能在回调中执行重业务逻辑。
 */
@FunctionalInterface
public interface ActorSlowTaskSink {
    void accept(ActorSlowTask slowTask);

    static ActorSlowTaskSink ignore() {
        return ignored -> {
        };
    }
}
