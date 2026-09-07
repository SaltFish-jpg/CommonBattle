package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.achievement.AchievementEventHandler;
import com.commonbattle.game.achievement.AchievementService;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.battle.BattleService;
import com.commonbattle.game.config.GameConfigRuntime;
import com.commonbattle.game.growth.GrowthService;
import com.commonbattle.game.player.event.PlayerEventDispatcher;
import com.commonbattle.game.shop.ShopService;
import com.commonbattle.game.task.TaskEventHandler;
import com.commonbattle.game.task.TaskService;

import java.util.Objects;
import java.util.Optional;

/**
 * 玩家业务消息绑定的一份配置运行时视图。
 * 业务处理应在消息入口解析一次并沿调用链传递，避免热更过程中同一条消息读到多个配置版本。
 */
public final class PlayerGameRuntime {
    private final long version;
    private final BagService bagService;
    private final ActivityService activityService;
    private final GrowthService growthService;
    private final ShopService shopService;
    private final BattleService battleService;
    private final TaskService taskService;
    private final AchievementService achievementService;
    private final PlayerEventDispatcher eventDispatcher;

    private PlayerGameRuntime(
            long version,
            BagService bagService,
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService,
            BattleService battleService,
            TaskService taskService,
            AchievementService achievementService,
            PlayerEventDispatcher eventDispatcher
    ) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.bagService = bagService;
        this.activityService = Objects.requireNonNull(activityService, "activityService");
        this.growthService = Objects.requireNonNull(growthService, "growthService");
        this.shopService = shopService;
        this.battleService = battleService;
        this.taskService = taskService;
        this.achievementService = achievementService;
        this.eventDispatcher = Objects.requireNonNull(eventDispatcher, "eventDispatcher");
    }

    public static PlayerGameRuntime fixed(ActivityService activityService, GrowthService growthService) {
        return new PlayerGameRuntime(0, null, activityService, growthService, null, null, null, null,
                PlayerEventDispatcher.defaults(activityService));
    }

    public static PlayerGameRuntime fixed(
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService
    ) {
        return new PlayerGameRuntime(0, null, activityService, growthService, shopService, null, null, null,
                PlayerEventDispatcher.defaults(activityService));
    }

    public static PlayerGameRuntime fixed(
            ActivityService activityService,
            GrowthService growthService,
            ShopService shopService,
            BattleService battleService
    ) {
        return new PlayerGameRuntime(0, null, activityService, growthService, shopService, battleService, null, null,
                PlayerEventDispatcher.defaults(activityService));
    }

    public static PlayerGameRuntime from(GameConfigRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new PlayerGameRuntime(
                runtime.version(),
                runtime.bagService(),
                runtime.activityService(),
                runtime.growthService(),
                runtime.shopService(),
                runtime.battleService(),
                runtime.taskService(),
                runtime.achievementService(),
                PlayerEventDispatcher.builder()
                        .add(new com.commonbattle.game.player.event.ActivityProgressEventHandler(runtime.activityService()))
                        .add(new TaskEventHandler(runtime.taskService()))
                        .add(new AchievementEventHandler(runtime.achievementService()))
                        .build()
        );
    }

    public long version() {
        return version;
    }

    public Optional<BagService> bagService() {
        return Optional.ofNullable(bagService);
    }

    public BagService requireBagService() {
        if (bagService == null) {
            throw new IllegalStateException("bag service is not available for fixed legacy runtime");
        }
        return bagService;
    }

    public ActivityService activityService() {
        return activityService;
    }

    public GrowthService growthService() {
        return growthService;
    }

    public ShopService requireShopService() {
        if (shopService == null) {
            throw new IllegalStateException("shop service is not available for this runtime");
        }
        return shopService;
    }

    public BattleService requireBattleService() {
        if (battleService == null) {
            throw new IllegalStateException("battle service is not available for this runtime");
        }
        return battleService;
    }

    public TaskService requireTaskService() {
        if (taskService == null) {
            throw new IllegalStateException("task service is not available for this runtime");
        }
        return taskService;
    }

    public AchievementService requireAchievementService() {
        if (achievementService == null) {
            throw new IllegalStateException("achievement service is not available for this runtime");
        }
        return achievementService;
    }

    public PlayerEventDispatcher eventDispatcher() {
        return eventDispatcher;
    }
}
