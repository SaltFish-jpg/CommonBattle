package com.commonbattle.game.bag;

import java.util.List;

/**
 * 通用奖励包。
 * 活动、任务、邮件等系统都可以复用它向背包发放物品。
 */
public record Reward(List<ItemStack> items) {
    public Reward {
        items = List.copyOf(items);
    }

    public static Reward of(ItemStack... items) {
        return new Reward(List.of(items));
    }
}
