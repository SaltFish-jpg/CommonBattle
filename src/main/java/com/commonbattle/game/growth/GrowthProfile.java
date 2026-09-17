package com.commonbattle.game.growth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 玩家养成状态。
 */
public final class GrowthProfile {
    private int level = 1;
    private int exp;
    private int stamina = GrowthSnapshot.DEFAULT_MAX_STAMINA;
    private int maxStamina = GrowthSnapshot.DEFAULT_MAX_STAMINA;
    private Instant staminaUpdatedAt = Instant.EPOCH;

    public int level() {
        return level;
    }

    public int exp() {
        return exp;
    }

    public int stamina() {
        return stamina;
    }

    public int maxStamina() {
        return maxStamina;
    }

    public Instant staminaUpdatedAt() {
        return staminaUpdatedAt;
    }

    public GrowthSnapshot snapshot() {
        return new GrowthSnapshot(level, exp, stamina, maxStamina, staminaUpdatedAt);
    }

    public void restore(GrowthSnapshot snapshot) {
        level = snapshot.level();
        exp = snapshot.exp();
        stamina = snapshot.stamina();
        maxStamina = snapshot.maxStamina();
        staminaUpdatedAt = snapshot.staminaUpdatedAt();
    }

    void addExp(int value, int expPerLevel) {
        exp += value;
        while (exp >= expPerLevel) {
            exp -= expPerLevel;
            level++;
        }
    }

    void recoverStamina(int configuredMaxStamina, int recoverAmount, Duration recoverPeriod, Instant now) {
        Objects.requireNonNull(recoverPeriod, "recoverPeriod");
        Objects.requireNonNull(now, "now");
        if (configuredMaxStamina <= 0) {
            throw new IllegalArgumentException("configuredMaxStamina must be positive");
        }
        if (recoverAmount <= 0) {
            throw new IllegalArgumentException("recoverAmount must be positive");
        }
        if (recoverPeriod.isZero() || recoverPeriod.isNegative()) {
            throw new IllegalArgumentException("recoverPeriod must be positive");
        }
        maxStamina = configuredMaxStamina;
        stamina = Math.min(stamina, maxStamina);
        if (now.isBefore(staminaUpdatedAt)) {
            return;
        }
        if (stamina >= maxStamina) {
            staminaUpdatedAt = now;
            return;
        }
        long periodMillis = recoverPeriod.toMillis();
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("recoverPeriod must be at least one millisecond");
        }
        long elapsedMillis = Duration.between(staminaUpdatedAt, now).toMillis();
        long periods = elapsedMillis / periodMillis;
        if (periods <= 0) {
            return;
        }
        int recovered = Math.toIntExact(Math.min(
                (long) maxStamina - stamina,
                periods * (long) recoverAmount
        ));
        stamina += recovered;
        staminaUpdatedAt = stamina >= maxStamina
                ? now
                : staminaUpdatedAt.plusMillis(periods * periodMillis);
    }

    boolean consumeStamina(int cost, Instant now) {
        Objects.requireNonNull(now, "now");
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive");
        }
        if (stamina < cost) {
            return false;
        }
        stamina -= cost;
        staminaUpdatedAt = now;
        return true;
    }
}
