package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorContext;

/**
 * 本服 ask 回调。
 * 成功和失败都会被投递回发起方 Actor 邮箱，调用方不应在目标 Actor 线程里继续执行业务。
 */
public interface LocalAskCallback<T> {
    void success(ActorContext context, T response);

    void failure(ActorContext context, Throwable error);
}
