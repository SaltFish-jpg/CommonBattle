package com.commonbattle.game.player.event;

import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.player.PlayerGameExecution;

import java.util.Objects;

/**
 * 把玩家业务事件转换为活动进度。
 * 当前示例先消费战斗结算事件，后续任务、商店、养成都可以通过同一模式扩展。
 */
public final class ActivityProgressEventHandler implements PlayerEventHandler {
    private final ActivityService activityService;

    public ActivityProgressEventHandler(ActivityService activityService) {
        this.activityService = Objects.requireNonNull(activityService, "activityService");
    }

    @Override
    public void handle(PlayerGameExecution execution, PlayerDomainEvent event) {
        int matched = activityService.onEvent(
                execution.profile().activities(),
                execution.activityAccess(),
                event
        );
        if (event instanceof BattleStageClearedEvent battle
                && !battle.activityId().isBlank()
                && battle.delta() > 0
                && matched == 0
                && !battle.replayed()) {
            activityService.increase(
                    execution.profile().activities(),
                    execution.activityAccess(),
                    battle.activityId(),
                    battle.delta()
            );
        }
    }
}
