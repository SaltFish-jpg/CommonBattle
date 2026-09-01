package com.commonbattle.actor;

/**
 * Actor 任务异常处理器。
 * 用于记录日志、上报告警或触发监督策略，默认只隔离异常并继续处理后续消息。
 */
@FunctionalInterface
public interface ActorFailureHandler {
    void onFailure(ActorFailure failure);

    static ActorFailureHandler ignore() {
        return ignored -> {
        };
    }
}
