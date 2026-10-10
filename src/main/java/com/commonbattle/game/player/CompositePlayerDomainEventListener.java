package com.commonbattle.game.player;

import com.commonbattle.game.player.event.PlayerDomainEvent;

import java.util.List;
import java.util.Objects;

/**
 * 玩家领域事件监听器组合。
 * 启动装配可用它把资料投影、审计和轻量本地派生串在同一个玩家 mailbox 回调边界内。
 */
public final class CompositePlayerDomainEventListener implements PlayerDomainEventListener {
    private final List<PlayerDomainEventListener> listeners;

    public CompositePlayerDomainEventListener(List<PlayerDomainEventListener> listeners) {
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    public static PlayerDomainEventListener of(PlayerDomainEventListener... listeners) {
        Objects.requireNonNull(listeners, "listeners");
        return new CompositePlayerDomainEventListener(List.of(listeners));
    }

    @Override
    public void onEvent(PlayerProfile profile, long eventRevision, PlayerDomainEvent event) {
        for (PlayerDomainEventListener listener : listeners) {
            listener.onEvent(profile, eventRevision, event);
        }
    }
}
