package com.commonbattle.game.agent;

import com.commonbattle.actor.ActorContext;

/**
 * 通用业务 Agent 请求处理器。
 * 实现方只会在目标 Agent 的 Actor 邮箱内被调用，可以安全读写该 Agent 自己拥有的状态。
 */
@FunctionalInterface
public interface BusinessAgentHandler {
    Object handle(ActorContext context, BusinessAgentRequest request);
}
