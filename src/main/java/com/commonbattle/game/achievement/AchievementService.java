package com.commonbattle.game.achievement;

import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.PlayerBag;
import com.commonbattle.game.player.event.PlayerDomainEvent;

import java.util.Objects;

/**
 * 玩家成就服务。
 * 成就进度由玩家业务事件推进，领奖时通过背包服务发放一次性奖励。
 */
public final class AchievementService {
    private final AchievementCatalog catalog;
    private final BagService bagService;

    public AchievementService(AchievementCatalog catalog, BagService bagService) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.bagService = Objects.requireNonNull(bagService, "bagService");
    }

    public void onEvent(PlayerAchievements achievements, PlayerDomainEvent event) {
        Objects.requireNonNull(achievements, "achievements");
        Objects.requireNonNull(event, "event");
        if (event.delta() <= 0 || event.replayed()) {
            return;
        }
        for (AchievementDefinition definition : catalog.definitions()) {
            if (definition.progressRule().matches(event)) {
                achievements.progress(definition.achievementId()).increase(event.delta());
            }
        }
    }

    public AchievementClaimResult claim(PlayerAchievements achievements, PlayerBag bag, String achievementId) {
        Objects.requireNonNull(achievements, "achievements");
        Objects.requireNonNull(bag, "bag");
        AchievementDefinition definition = catalog.require(achievementId);
        AchievementProgress progress = achievements.progress(achievementId);
        if (progress.claimed()) {
            throw new IllegalStateException("Achievement reward already claimed: " + achievementId);
        }
        if (progress.value() < definition.threshold()) {
            throw new IllegalStateException("Achievement reward is not ready: " + achievementId);
        }
        // 成就领奖边界：先标记领取，再发奖励，保证重入或重复请求不会重复领奖。
        progress.claim();
        return new AchievementClaimResult(achievementId, progress.value(), bagService.grant(bag, definition.reward()));
    }
}
