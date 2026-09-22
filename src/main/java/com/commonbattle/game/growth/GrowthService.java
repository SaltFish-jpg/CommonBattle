package com.commonbattle.game.growth;

import com.commonbattle.game.GameBusinessErrorCodes;
import com.commonbattle.game.GameBusinessIllegalArgumentException;
import com.commonbattle.game.bag.BagService;
import com.commonbattle.game.bag.ItemStack;
import com.commonbattle.game.bag.PlayerBag;

import java.time.Duration;
import java.time.Instant;
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
    private final int maxStamina;
    private final int staminaRecoverAmount;
    private final Duration staminaRecoverPeriod;

    public GrowthService(BagService bagService, String expItemId, int expPerItem, int expPerLevel) {
        this(bagService, expItemId, expPerItem, expPerLevel,
                GrowthSnapshot.DEFAULT_MAX_STAMINA, 1, Duration.ofMinutes(5));
    }

    public GrowthService(
            BagService bagService,
            String expItemId,
            int expPerItem,
            int expPerLevel,
            int maxStamina,
            int staminaRecoverAmount,
            Duration staminaRecoverPeriod
    ) {
        this.bagService = Objects.requireNonNull(bagService, "bagService");
        this.expItemId = Objects.requireNonNull(expItemId, "expItemId");
        if (expPerItem <= 0 || expPerLevel <= 0) {
            throw new IllegalArgumentException("exp config must be positive");
        }
        if (maxStamina <= 0) {
            throw new IllegalArgumentException("maxStamina must be positive");
        }
        if (staminaRecoverAmount <= 0) {
            throw new IllegalArgumentException("staminaRecoverAmount must be positive");
        }
        Objects.requireNonNull(staminaRecoverPeriod, "staminaRecoverPeriod");
        if (staminaRecoverPeriod.isZero() || staminaRecoverPeriod.isNegative()) {
            throw new IllegalArgumentException("staminaRecoverPeriod must be positive");
        }
        this.expPerItem = expPerItem;
        this.expPerLevel = expPerLevel;
        this.maxStamina = maxStamina;
        this.staminaRecoverAmount = staminaRecoverAmount;
        this.staminaRecoverPeriod = staminaRecoverPeriod;
    }

    public GrowthResult useExpItems(PlayerBag bag, GrowthProfile profile, int count) {
        if (count <= 0) {
            throw new GameBusinessIllegalArgumentException(
                    GameBusinessErrorCodes.GROWTH_INVALID_EXP_ITEM_COUNT,
                    "count must be positive"
            );
        }
        int beforeLevel = profile.level();
        int beforeExp = profile.exp();
        // 养成消耗边界：先扣除背包材料，扣除成功后才推进经验，保证失败不改变养成状态。
        bagService.consume(bag, new ItemStack(expItemId, count));
        profile.addExp(expPerItem * count, expPerLevel);
        return new GrowthResult(beforeLevel, profile.level(), beforeExp, profile.exp());
    }

    public GrowthRecoveryResult recoverStamina(GrowthProfile profile, Instant now) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(now, "now");
        int before = profile.stamina();
        Instant beforeUpdatedAt = profile.staminaUpdatedAt();
        profile.recoverStamina(maxStamina, staminaRecoverAmount, staminaRecoverPeriod, now);
        return new GrowthRecoveryResult(
                before,
                profile.stamina(),
                profile.maxStamina(),
                beforeUpdatedAt,
                profile.staminaUpdatedAt()
        );
    }

    public boolean consumeStamina(GrowthProfile profile, int cost, Instant now) {
        Objects.requireNonNull(profile, "profile");
        recoverStamina(profile, now);
        return profile.consumeStamina(cost, now);
    }
}
