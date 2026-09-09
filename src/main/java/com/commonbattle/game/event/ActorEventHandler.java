package com.commonbattle.game.event;

import com.commonbattle.actor.ActorContext;

/**
 * 版本事件的 Actor 邮箱内处理器。
 * Cluster/Netty 回调只负责投递，真正业务逻辑必须在该处理器内串行执行。
 */
@FunctionalInterface
public interface ActorEventHandler {
    void handle(ActorContext context, VersionedEvent event);
}
