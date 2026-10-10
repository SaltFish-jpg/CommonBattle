package com.commonbattle.game.player.event;

import java.util.Map;

/**
 * 玩家邮箱内的业务事件。
 * 事件只在当前玩家 Actor 消息中同步分发，用于活动、任务、成就等模块订阅业务结果。
 */
public interface PlayerDomainEvent {
    String type();

    String subject();

    int delta();

    default Map<String, String> attributes() {
        return Map.of();
    }

    default boolean replayed() {
        return false;
    }
}
