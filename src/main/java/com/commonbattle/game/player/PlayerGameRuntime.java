package com.commonbattle.game.player;

import com.commonbattle.game.activity.ActivityService;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.config.GameConfigRuntime;
import com.commonbattle.game.growth.GrowthService;

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

    private PlayerGameRuntime(
            long version,
            BagService bagService,
            ActivityService activityService,
            GrowthService growthService
    ) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.bagService = bagService;
        this.activityService = Objects.requireNonNull(activityService, "activityService");
        this.growthService = Objects.requireNonNull(growthService, "growthService");
    }

    public static PlayerGameRuntime fixed(ActivityService activityService, GrowthService growthService) {
        return new PlayerGameRuntime(0, null, activityService, growthService);
    }

    public static PlayerGameRuntime from(GameConfigRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return new PlayerGameRuntime(
                runtime.version(),
                runtime.bagService(),
                runtime.activityService(),
                runtime.growthService()
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
}
