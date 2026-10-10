package com.commonbattle.game.player;

import com.commonbattle.game.achievement.AchievementClaimResult;
import com.commonbattle.game.activity.ActivityClaimResult;
import com.commonbattle.game.bag.BagResult;
import com.commonbattle.game.battle.BattleSettlementResult;
import com.commonbattle.game.growth.GrowthResult;
import com.commonbattle.game.shop.ShopPurchaseResult;
import com.commonbattle.game.task.TaskClaimResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 示例玩家业务命令目录。
 * 新增玩家业务命令时先登记到这里，再补 codec 与 handler 测试，避免操作名、启动绑定和协议覆盖分散漂移。
 */
public final class PlayerBusinessCommandCatalog {
    private static final List<PlayerBusinessCommandDescriptor> DESCRIPTORS = List.of(
            new PlayerBusinessCommandDescriptor("bag", PlayerBusinessOperations.BAG_CONSUME_ITEM,
                    ConsumeBagItemCommand.class, BagResult.class),
            new PlayerBusinessCommandDescriptor("activity", PlayerBusinessOperations.ACTIVITY_PROGRESS,
                    ActivityProgressCommand.class, PlayerBusinessAck.class),
            new PlayerBusinessCommandDescriptor("activity", PlayerBusinessOperations.ACTIVITY_CLAIM,
                    ClaimActivityCommand.class, ActivityClaimResult.class),
            new PlayerBusinessCommandDescriptor("growth", PlayerBusinessOperations.GROWTH_USE_EXP_ITEMS,
                    UseExpItemsCommand.class, GrowthResult.class),
            new PlayerBusinessCommandDescriptor("shop", PlayerBusinessOperations.SHOP_BUY,
                    BuyShopItemCommand.class, ShopPurchaseResult.class),
            new PlayerBusinessCommandDescriptor("shop", PlayerBusinessOperations.SHOP_BUY,
                    BuyShopItemAsyncCommand.class, ShopPurchaseResult.class),
            new PlayerBusinessCommandDescriptor("battle", PlayerBusinessOperations.BATTLE_CLEAR_STAGE,
                    BattleStageClearCommand.class, BattleSettlementResult.class),
            new PlayerBusinessCommandDescriptor("battle", PlayerBusinessOperations.BATTLE_SWEEP_STAGE,
                    BattleStageSweepCommand.class, BattleSettlementResult.class),
            new PlayerBusinessCommandDescriptor("task", PlayerBusinessOperations.TASK_CLAIM,
                    ClaimTaskCommand.class, TaskClaimResult.class),
            new PlayerBusinessCommandDescriptor("achievement", PlayerBusinessOperations.ACHIEVEMENT_CLAIM,
                    ClaimAchievementCommand.class, AchievementClaimResult.class)
    );
    private static final List<String> OPERATIONS = uniqueOperations(DESCRIPTORS);
    private static final PlayerBusinessCommandCatalogView VIEW = PlayerBusinessCommandCatalog::stats;

    private PlayerBusinessCommandCatalog() {
    }

    public static List<PlayerBusinessCommandDescriptor> descriptors() {
        return DESCRIPTORS;
    }

    public static List<String> operations() {
        return OPERATIONS;
    }

    public static PlayerBusinessCommandCatalogView view() {
        return VIEW;
    }

    public static PlayerBusinessCommandCatalogStats stats() {
        Map<String, Integer> domains = new LinkedHashMap<>();
        for (PlayerBusinessCommandDescriptor descriptor : DESCRIPTORS) {
            domains.merge(descriptor.domain(), 1, Integer::sum);
        }
        int commandTypes = DESCRIPTORS.stream()
                .map(PlayerBusinessCommandDescriptor::commandType)
                .collect(Collectors.toSet())
                .size();
        return new PlayerBusinessCommandCatalogStats(DESCRIPTORS.size(), OPERATIONS.size(), commandTypes, domains);
    }

    public static List<PlayerBusinessCommandDescriptor> descriptorsByOperation(String operation) {
        Objects.requireNonNull(operation, "operation");
        return DESCRIPTORS.stream()
                .filter(descriptor -> descriptor.operation().equals(operation))
                .toList();
    }

    private static List<String> uniqueOperations(List<PlayerBusinessCommandDescriptor> descriptors) {
        List<String> operations = new ArrayList<>();
        for (PlayerBusinessCommandDescriptor descriptor : descriptors) {
            if (!operations.contains(descriptor.operation())) {
                operations.add(descriptor.operation());
            }
        }
        return List.copyOf(operations);
    }
}
