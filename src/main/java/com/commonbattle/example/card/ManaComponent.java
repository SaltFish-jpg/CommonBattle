package com.commonbattle.example.card;

import com.commonbattle.core.Component;

public final class ManaComponent implements Component {
    private final int max;
    private int current;

    public ManaComponent(int max) {
        this.max = max;
        this.current = max;
    }

    public int current() {
        return current;
    }

    public void spend(int amount) {
        if (amount > current) {
            throw new IllegalStateException("not enough mana");
        }
        current -= amount;
    }

    public void refill() {
        current = max;
    }
}
