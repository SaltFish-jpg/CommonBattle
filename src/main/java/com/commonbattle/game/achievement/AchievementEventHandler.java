package com.commonbattle.game.achievement;

import com.commonbattle.game.player.PlayerGameExecution;
import com.commonbattle.game.player.event.PlayerDomainEvent;
import com.commonbattle.game.player.event.PlayerEventHandler;

import java.util.Objects;

/**
 * 玩家业务事件到成就进度的桥接处理器。
 */
public final class AchievementEventHandler implements PlayerEventHandler {
    private final AchievementService achievementService;

    public AchievementEventHandler(AchievementService achievementService) {
        this.achievementService = Objects.requireNonNull(achievementService, "achievementService");
    }

    @Override
    public void handle(PlayerGameExecution execution, PlayerDomainEvent event) {
        achievementService.onEvent(execution.profile().achievements(), event);
    }
}
