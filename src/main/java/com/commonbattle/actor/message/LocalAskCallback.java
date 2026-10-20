package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorContext;

/**
 * 本服 ask 回调。
 * 成功和失败都会被投递回发起方 Actor 邮箱，调用方不应在目标 Actor 线程里继续执行业务。
 */
public interface LocalAskCallback<T> {
    void success(ActorContext context, T response);

    void failure(ActorContext context, Throwable error);

    /**
     * 结果因请求方 mailbox 背压或关闭而无法回投时调用。
     * 该方法不在 Actor 邮箱内，只允许清理并发状态、记录指标或日志，不能访问业务聚合状态。
     */
    default void callbackDeliveryFailed(AgentDeliveryResult delivery) {
    }
}
