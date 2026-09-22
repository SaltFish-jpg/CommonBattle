package com.commonbattle.game.bag;

import com.commonbattle.game.GameBusinessErrorCodes;
import com.commonbattle.game.GameBusinessIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 背包结算服务。
 * 负责统一处理奖励入包和物品消耗，调用方负责保证在玩家 Agent 串行上下文内执行。
 */
public final class BagService {
    private final ItemCatalog catalog;

    public BagService(ItemCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public BagResult grant(PlayerBag bag, Reward reward) {
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(reward, "reward");
        List<BagChange> changes = new ArrayList<>();
        for (ItemStack item : reward.items()) {
            ItemDefinition definition = catalog.require(item.itemId());
            validateStack(definition, item.count());
            int before = bag.count(item.itemId());
            bag.add(item.itemId(), item.count());
            changes.add(new BagChange(item.itemId(), before, bag.count(item.itemId())));
        }
        return new BagResult(changes);
    }

    public BagResult consume(PlayerBag bag, ItemStack cost) {
        Objects.requireNonNull(bag, "bag");
        Objects.requireNonNull(cost, "cost");
        validate(cost);
        int before = bag.count(cost.itemId());
        bag.remove(cost.itemId(), cost.count());
        return new BagResult(List.of(new BagChange(cost.itemId(), before, bag.count(cost.itemId()))));
    }

    public void validate(Reward reward) {
        Objects.requireNonNull(reward, "reward");
        for (ItemStack item : reward.items()) {
            validate(item);
        }
    }

    public void validate(ItemStack item) {
        Objects.requireNonNull(item, "item");
        ItemDefinition definition = catalog.require(item.itemId());
        validateStack(definition, item.count());
    }

    private void validateStack(ItemDefinition definition, int count) {
        if (count > definition.stackLimit() && definition.stackLimit() == 1) {
            throw new GameBusinessIllegalArgumentException(
                    GameBusinessErrorCodes.BAG_NOT_STACKABLE,
                    "Item " + definition.itemId() + " can not stack"
            );
        }
    }
}
