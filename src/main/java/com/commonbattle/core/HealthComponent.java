package com.commonbattle.core;

public final class HealthComponent implements Component {
    private final int max;
    private int current;

    public HealthComponent(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("max health must be positive");
        }
        this.max = max;
        this.current = max;
    }

    public int max() {
        return max;
    }

    public int current() {
        return current;
    }

    public boolean alive() {
        return current > 0;
    }

    public int damage(int amount) {
        int applied = Math.max(0, Math.min(current, amount));
        current -= applied;
        return applied;
    }

    public int heal(int amount) {
        int applied = Math.max(0, Math.min(max - current, amount));
        current += applied;
        return applied;
    }
}
