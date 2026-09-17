package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityAccessContext;
import com.commonbattle.game.battle.BattleStageDefinition;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.battle.BattleStaminaNotEnoughException;
import com.commonbattle.game.growth.GrowthRecoveryResult;
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
        pushActivitySnapshot();
        pushTaskSnapshot();
        pushAchievementSnapshot();
    }

    public void consumeBattleStaminaForNewSettlement(BattleStageDefinition definition, String settlementId) {
        Objects.requireNonNull(definition, "definition");
        settlementId = Objects.requireNonNullElse(settlementId, "");
        if (profile.battle().replay(settlementId, definition.stageId()).isPresent()) {
            return;
        }
        if (definition.staminaCost() <= 0) {
            return;
        }
        GrowthRecoveryResult recovery = runtime.growthService().recoverStamina(profile.growth(), activityAccess.now());
        if (!runtime.growthService().consumeStamina(profile.growth(), definition.staminaCost(), activityAccess.now())) {
            if (recovery.changed()) {
                pushGrowthSnapshot();
            }
            throw new BattleStaminaNotEnoughException(
                    definition.stageId(),
                    definition.staminaCost(),
                    profile.growth().stamina()
            );
        }
        // 战斗入口资源边界：先按当前时间恢复体力，再扣除本次战斗消耗，成功后才允许进入战斗结算。
        pushGrowthSnapshot();
    }
}
