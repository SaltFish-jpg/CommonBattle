package com.commonbattle.game.growth;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;

import java.util.Objects;

/**
 * 玩家养成服务。
 * 当前示例实现经验药水消耗，后续可扩展装备强化、技能升级、伙伴培养等结算。
 */
public final class GrowthService {
    private final BagService bagService;
    private final String expItemId;
    private final int expPerItem;
    private final int expPerLevel;

    public GrowthService(BagService bagService, String expItemId, int expPerItem, int expPerLevel) {
        this.bagService = Objects.requireNonNull(bagService, "bagService");
        this.expItemId = Objects.requireNonNull(expItemId, "expItemId");
        if (expPerItem <= 0 || expPerLevel <= 0) {
            throw new IllegalArgumentException("exp config must be positive");
        }
        this.expPerItem = expPerItem;
        this.expPerLevel = expPerLevel;
    }

    public GrowthResult useExpItems(PlayerBag bag, GrowthProfile profile, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        int beforeLevel = profile.level();
        int beforeExp = profile.exp();
        // 养成消耗边界：先扣除背包材料，扣除成功后才推进经验，保证失败不改变养成状态。
        bagService.consume(bag, new ItemStack(expItemId, count));
        profile.addExp(expPerItem * count, expPerLevel);
        return new GrowthResult(beforeLevel, profile.level(), beforeExp, profile.exp());
    }
}
