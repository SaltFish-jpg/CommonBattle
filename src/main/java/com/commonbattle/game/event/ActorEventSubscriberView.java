package com.commonbattle.game.event;

/**
 * 事件到 Actor 邮箱投递器的只读观测视图。
 */
public interface ActorEventSubscriberView {
    ActorEventSubscriberStats stats();
}
