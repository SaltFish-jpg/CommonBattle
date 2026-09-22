package com.commonbattle.game.activity;

import com.commonbattle.game.GameBusinessErrorCodes;
import com.commonbattle.game.GameBusinessIllegalArgumentException;
import com.commonbattle.game.GameBusinessIllegalStateException;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.player.event.PlayerDomainEvent;

import java.util.Objects;

/**
 * 活动结算服务。
 * 负责进度推进、领取资格判断和奖励发放边界，具体奖励入包委托背包服务。
 */
public final class ActivityService {
    private final ActivityCatalog catalog;
    private final BagService bagService;

    public ActivityService(ActivityCatalog catalog, BagService bagService) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bagService = Objects.requireNonNull(bagService, "bagService");
    }

    public void recordLogin(PlayerActivities activities, String activityId) {
        recordLogin(activities, ActivityAccessContext.alwaysAllowed(), activityId);
    }

    public void recordLogin(PlayerActivities activities, ActivityAccessContext access, String activityId) {
        ActivityDefinition definition = catalog.require(activityId);
        ensureAvailable(definition, access);
        if (definition.type() != ActivityType.LOGIN) {
            throw new GameBusinessIllegalArgumentException(
                    GameBusinessErrorCodes.ACTIVITY_WRONG_TYPE,
                    activityId + " is not a login activity"
            );
        }
        ActivityProgress progress = activities.progress(activityId);
        if (progress.value() == 0) {
            progress.increase(1);
        }
    }

    public void increase(PlayerActivities activities, String activityId, int delta) {
        increase(activities, ActivityAccessContext.alwaysAllowed(), activityId, delta);
    }

    public void increase(PlayerActivities activities, ActivityAccessContext access, String activityId, int delta) {
        if (delta <= 0) {
            throw new GameBusinessIllegalArgumentException(
                    GameBusinessErrorCodes.ACTIVITY_INVALID_DELTA,
                    "delta must be positive"
            );
        }
        ActivityDefinition definition = catalog.require(activityId);
        ensureAvailable(definition, access);
        if (definition.type() != ActivityType.COUNTER) {
            throw new GameBusinessIllegalArgumentException(
                    GameBusinessErrorCodes.ACTIVITY_WRONG_TYPE,
                    activityId + " is not a counter activity"
            );
        }
        activities.progress(activityId).increase(delta);
    }

    public ActivityClaimResult claim(PlayerActivities activities, PlayerBag bag, String activityId) {
        return claim(activities, bag, ActivityAccessContext.alwaysAllowed(), activityId);
    }

    public ActivityClaimResult claim(PlayerActivities activities, PlayerBag bag, ActivityAccessContext access, String activityId) {
        ActivityDefinition definition = catalog.require(activityId);
        ensureAvailable(definition, access);
        ActivityProgress progress = activities.progress(activityId);
        if (progress.claimed()) {
            throw new GameBusinessIllegalStateException(
                    GameBusinessErrorCodes.ACTIVITY_REWARD_ALREADY_CLAIMED,
                    "Activity reward already claimed: " + activityId
            );
        }
        if (progress.value() < definition.threshold()) {
            throw new GameBusinessIllegalStateException(
                    GameBusinessErrorCodes.ACTIVITY_REWARD_NOT_READY,
                    "Activity reward is not ready: " + activityId
            );
        }
        // 活动领奖边界：先标记已领取，再发奖励，避免奖励发放链路触发重入时重复领取。
        progress.claim();
        return new ActivityClaimResult(
                activityId,
                progress.value(),
                bagService.grant(bag, definition.reward())
        );
    }

    public int onEvent(PlayerActivities activities, ActivityAccessContext access, PlayerDomainEvent event) {
        Objects.requireNonNull(activities, "activities");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(event, "event");
        if (event.delta() <= 0 || event.replayed()) {
            return 0;
        }
        int matched = 0;
        for (ActivityDefinition definition : catalog.definitions()) {
            if (definition.type() == ActivityType.COUNTER && definition.progressRule().matches(event)) {
                ensureAvailable(definition, access);
                activities.progress(definition.activityId()).increase(event.delta());
                matched++;
            }
        }
        return matched;
    }

    private void ensureAvailable(ActivityDefinition definition, ActivityAccessContext access) {
        // 活动结算入口边界：开放时间和参与条件必须先于进度、领奖、发包执行。
        if (!definition.schedule().isOpen(access)) {
            throw new GameBusinessIllegalStateException(
                    GameBusinessErrorCodes.ACTIVITY_NOT_OPEN,
                    "Activity is not open: " + definition.activityId()
            );
        }
        if (!definition.participation().allows(access)) {
            throw new GameBusinessIllegalStateException(
                    GameBusinessErrorCodes.ACTIVITY_NOT_ELIGIBLE,
                    "Activity participant is not eligible: " + definition.activityId()
            );
        }
    }
}
