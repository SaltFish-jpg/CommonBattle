package com.commonbattle.game.player;

import com.commonbattle.game.bag.BagChange;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.player.event.GrowthLevelChangedEvent;

import java.util.List;

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
        GrowthService service = execution.runtime().growthService();
        String expItemId = service.expItemId();
        int beforeItemCount = execution.profile().bag().count(expItemId);
        GrowthResult result = service.useExpItems(
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
        execution.publishBagChanged(operation(), false, new BagResult(List.of(new BagChange(
                expItemId,
                beforeItemCount,
                execution.profile().bag().count(expItemId)
        ))));
        execution.pushGrowthSnapshot();
        execution.pushBagSnapshot();
        return result;
    }
}
