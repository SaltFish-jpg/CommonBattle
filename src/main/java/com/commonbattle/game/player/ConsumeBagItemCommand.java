package com.commonbattle.game.player;

import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.bag.ItemStack;

import java.util.Objects;

/**
 * 直接消耗玩家背包物品的示例业务命令。
 * 客户端、RPC 回包或本服业务可用该命令把背包修改统一收敛到玩家 mailbox 内执行。
 */
public record ConsumeBagItemCommand(String itemId, int count) implements PlayerBusinessCommand<BagResult> {
    public ConsumeBagItemCommand {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    @Override
    public String operation() {
        return PlayerBusinessOperations.BAG_CONSUME_ITEM;
    }

    @Override
    public BagResult execute(PlayerGameExecution execution) {
        BagResult result = execution.runtime().requireBagService()
                .consume(execution.profile().bag(), new ItemStack(itemId, count));
        execution.publishBagChanged(operation(), false, result);
        execution.pushBagSnapshot();
        return result;
    }
}
