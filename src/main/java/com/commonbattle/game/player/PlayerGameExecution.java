package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.player.event.PlayerDomainEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 玩家邮箱内的一次业务执行上下文。
 * 自定义玩家命令、RPC 回包和定时器逻辑可使用它访问玩家状态和本消息固定的配置版本。
 */
public record PlayerGameExecution(
        PlayerProfile profile,
        PlayerGameRuntime runtime,
        ActivityAccessContext activityAccess,
        Consumer<PlayerDomainEvent> externalEventPublisher
) {
    public PlayerGameExecution {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(activityAccess, "activityAccess");
        Objects.requireNonNull(externalEventPublisher, "externalEventPublisher");
    }

    public PlayerGameExecution(PlayerProfile profile, PlayerGameRuntime runtime, ActivityAccessContext activityAccess) {
        this(profile, runtime, activityAccess, ignored -> {
        });
    }

    public void publish(PlayerDomainEvent event) {
        runtime.eventDispatcher().dispatch(this, event);
        externalEventPublisher.accept(event);
    }
}
