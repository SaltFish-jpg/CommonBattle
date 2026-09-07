package com.commonbattle.game.player.event;

import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.player.PlayerGameExecution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 玩家邮箱内同步事件分发器。
 * 一个业务命令完成核心结算后，可发布事件给活动、任务、成就等模块继续推进派生状态。
 */
public final class PlayerEventDispatcher {
    private static final PlayerEventDispatcher EMPTY = new PlayerEventDispatcher(List.of());

    private final List<PlayerEventHandler> handlers;

    public PlayerEventDispatcher(List<PlayerEventHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public static PlayerEventDispatcher empty() {
        return EMPTY;
    }

    public static PlayerEventDispatcher defaults(ActivityService activityService) {
        Objects.requireNonNull(activityService, "activityService");
        return new PlayerEventDispatcher(List.of(new ActivityProgressEventHandler(activityService)));
    }

    public static Builder builder() {
        return new Builder();
    }

    public void dispatch(PlayerGameExecution execution, PlayerDomainEvent event) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(event, "event");
        for (PlayerEventHandler handler : handlers) {
            handler.handle(execution, event);
        }
    }

    public static final class Builder {
        private final List<PlayerEventHandler> handlers = new ArrayList<>();

        public Builder add(PlayerEventHandler handler) {
            handlers.add(Objects.requireNonNull(handler, "handler"));
            return this;
        }

        public PlayerEventDispatcher build() {
            return new PlayerEventDispatcher(handlers);
        }
    }
}
