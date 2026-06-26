package com.commonbattle.core;

/**
 * Scoped mutable context for one damage settlement.
 * Before-damage triggers can inspect or modify this object before health is changed.
 */
public final class DamageContext {
    private final EntityId source;
    private final EntityId target;
    private int amount;

    public DamageContext(EntityId source, EntityId target, int amount) {
        this.source = source;
        this.target = target;
        this.amount = amount;
    }

    public EntityId source() {
        return source;
    }

    public EntityId target() {
        return target;
    }

    public int amount() {
        return amount;
    }

    public void increaseAmount(int delta) {
        amount += delta;
    }
}
