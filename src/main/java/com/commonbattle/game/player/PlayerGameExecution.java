package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.player.event.PlayerDomainEvent;
import com.commonbattle.game.session.PlayerOutboundTopicPolicies;

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
        Consumer<PlayerDomainEvent> externalEventPublisher,
        PlayerPushPort pushes
) {
    public PlayerGameExecution {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(activityAccess, "activityAccess");
        Objects.requireNonNull(externalEventPublisher, "externalEventPublisher");
        Objects.requireNonNull(pushes, "pushes");
    }

    public PlayerGameExecution(PlayerProfile profile, PlayerGameRuntime runtime, ActivityAccessContext activityAccess) {
        this(profile, runtime, activityAccess, ignored -> {
        }, PlayerPushPort.NOOP);
    }

    public PlayerGameExecution(
            PlayerProfile profile,
            PlayerGameRuntime runtime,
            ActivityAccessContext activityAccess,
            Consumer<PlayerDomainEvent> externalEventPublisher
    ) {
        this(profile, runtime, activityAccess, externalEventPublisher, PlayerPushPort.NOOP);
    }

    public void publish(PlayerDomainEvent event) {
        runtime.eventDispatcher().dispatch(this, event);
        externalEventPublisher.accept(event);
    }

    public void pushSelf(String topic, Object payload) {
        pushes.push(profile.playerId(), topic, payload);
    }

    public void pushBagSnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.BAG_SNAPSHOT, PlayerPushPayloads.bag(profile.bag().snapshot()));
    }

    public void pushActivitySnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.ACTIVITY_PROGRESS, PlayerPushPayloads.activities(profile.activities().snapshot()));
    }

    public void pushGrowthSnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.GROWTH_SNAPSHOT, PlayerPushPayloads.growth(profile.growth().snapshot()));
    }

    public void pushShopSnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.SHOP_SNAPSHOT, PlayerPushPayloads.shop(profile.shop().snapshot()));
    }

    public void pushBattleSnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.BATTLE_SNAPSHOT, PlayerPushPayloads.battle(profile.battle().snapshot()));
    }

    public void pushTaskSnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.TASK_PROGRESS, PlayerPushPayloads.tasks(profile.tasks().snapshot()));
    }

    public void pushAchievementSnapshot() {
        pushSelf(PlayerOutboundTopicPolicies.ACHIEVEMENT_PROGRESS,
                PlayerPushPayloads.achievements(profile.achievements().snapshot()));
    }

    public void pushBattleSettlementSnapshots(BattleSettlementResult result) {
        pushBattleSnapshot();
        pushBagSnapshot();
        if (!result.progressActivityId().isBlank() && result.progressDelta() > 0) {
            pushActivitySnapshot();
        }
    }
}
