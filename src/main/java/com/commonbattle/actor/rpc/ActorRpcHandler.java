package com.commonbattle.actor.rpc;

import com.commonbattle.actor.ActorContext;
import com.commonbattle.actor.message.AgentDeliveryResult;

/**
 * Actor 绑定 RPC 的业务处理模板。
 * 成功和失败都会回到 owner 邮箱串行执行；失败分支拿到统一投递结果，避免业务各自识别底层异常类型。
 */
public interface ActorRpcHandler<T> {
    void success(ActorContext context, T response);

    void failure(ActorContext context, AgentDeliveryResult delivery, Throwable error);
}
