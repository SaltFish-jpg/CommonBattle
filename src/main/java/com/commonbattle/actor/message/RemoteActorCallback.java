package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorContext;

/**
 * 绑定请求方 Actor 邮箱的远程调用回调。
 * success 和 failure 始终在请求方 mailbox 内执行；其余通知只用于线程安全的指标记录，不能访问业务状态。
 */
public interface RemoteActorCallback<T> {
    void success(ActorContext context, T response);

    void failure(ActorContext context, AgentDeliveryResult delivery, Throwable error);

    /**
     * 回包到达后因请求方 mailbox 背压或关闭而无法投递时调用。
     * 该方法运行在 RPC 或超时线程，只允许记录指标、日志或触发无状态告警。
     */
    default void callbackDeliveryFailed(AgentDeliveryResult delivery) {
    }

    /**
     * 已超时或已完成的调用再次收到回包时调用。
     * 该方法可能运行在 RPC 线程，只允许执行线程安全的无状态观测逻辑。
     */
    default void lateResponse() {
    }
}
