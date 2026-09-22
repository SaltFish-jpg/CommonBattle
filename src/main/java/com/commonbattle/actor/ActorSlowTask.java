package com.commonbattle.actor;

import java.time.Duration;
import java.util.Objects;

/**
 * Actor 慢任务事件。
 * ActorSystem 在业务消息执行超过慢任务阈值时产生该事件，监听方只应记录或告警，不应反向干预当前执行。
 */
public record ActorSlowTask(
        ActorRef actor,
        ActorTaskCategory category,
        Duration elapsed,
        Duration threshold
) {
    public ActorSlowTask {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(threshold, "threshold");
        if (elapsed.isNegative() || threshold.isNegative()) {
            throw new IllegalArgumentException("slow task durations must not be negative");
        }
    }
}
