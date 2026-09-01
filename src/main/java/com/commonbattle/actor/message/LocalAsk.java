package com.commonbattle.actor.message;

import com.commonbattle.actor.ActorContext;

/**
 * 本服 Agent 查询请求。
 * 查询逻辑在目标 Actor 邮箱内执行，只能读取或操作目标 Actor 自己拥有的状态。
 */
@FunctionalInterface
public interface LocalAsk<T> {
    T answer(ActorContext context);
}
