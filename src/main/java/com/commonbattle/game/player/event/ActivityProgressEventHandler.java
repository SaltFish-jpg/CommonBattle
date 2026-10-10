package com.commonbattle.game.player.event;

import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.player.PlayerGameExecution;

import java.util.Objects;

/**
 * 把玩家业务事件转换为活动进度。
 * 事件规则可覆盖战斗、商店、养成等业务，进度变化后在当前玩家 mailbox 内推送活动快照。
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
        boolean progressed = matched > 0;
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
            progressed = true;
        }
        if (progressed) {
            execution.pushActivitySnapshot();
        }
    }
}
