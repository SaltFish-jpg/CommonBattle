package com.commonbattle.core;

/**
 * 单位怒气条状态。
 * 可挂到任意实体上，用于描述普通攻击造成伤害和被普通攻击命中时获得多少怒气。
 */
public final class RageEnergyComponent implements Component {
    private final int max;
    private final int gainOnAttack;
    private final int gainOnDamaged;
    private int current;

    public RageEnergyComponent(int max, int gainOnAttack, int gainOnDamaged) {
        this(max, 0, gainOnAttack, gainOnDamaged);
    }

    public RageEnergyComponent(int max, int current, int gainOnAttack, int gainOnDamaged) {
        if (max <= 0) {
            throw new IllegalArgumentException("max rage must be positive");
        }
        if (current < 0 || current > max) {
            throw new IllegalArgumentException("current rage must be between 0 and max");
        }
        if (gainOnAttack < 0 || gainOnDamaged < 0) {
            throw new IllegalArgumentException("rage gains must be non-negative");
        }
        this.max = max;
        this.current = current;
        this.gainOnAttack = gainOnAttack;
        this.gainOnDamaged = gainOnDamaged;
    }

    public int max() {
        return max;
    }

    public int current() {
        return current;
    }

    public int gainOnAttack() {
        return gainOnAttack;
    }

    public int gainOnDamaged() {
        return gainOnDamaged;
    }

    public boolean full() {
        return current >= max;
    }

    public int add(int amount) {
        int applied = Math.max(0, Math.min(max - current, amount));
        current += applied;
        return applied;
    }

    public boolean consumeFull() {
        if (!full()) {
            return false;
        }
        current = 0;
        return true;
    }
}
