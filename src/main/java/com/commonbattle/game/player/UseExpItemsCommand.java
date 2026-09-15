package com.commonbattle.game.player;

import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.player.event.GrowthLevelChangedEvent;

/**
 * 使用经验道具的养成示例业务命令。
 */
public record UseExpItemsCommand(int count) implements PlayerBusinessCommand<GrowthResult> {
    public UseExpItemsCommand {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS;
    }

    @Override
    public GrowthResult execute(PlayerGameExecution execution) {
        GrowthResult result = execution.runtime().growthService().useExpItems(
                execution.profile().bag(),
                execution.profile().growth(),
                count
        );
        if (result.afterLevel() > result.beforeLevel()) {
            execution.publish(new GrowthLevelChangedEvent(
                    execution.profile().playerId(),
                    result.beforeLevel(),
                    result.afterLevel()
            ));
        }
        execution.pushGrowthSnapshot();
        execution.pushBagSnapshot();
        return result;
    }
}
