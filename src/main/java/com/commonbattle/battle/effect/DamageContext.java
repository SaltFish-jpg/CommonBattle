package com.commonbattle.battle.effect;



import com.commonbattle.battle.state.EntityId;
/**
 * 单次伤害结算的作用域可变上下文。
 * 伤害前触发器可以在生命值变化前检查或修改该对象。
 */
public final class DamageContext {
    private final EntityId source;
    private final EntityId target;
    private final DamageKind kind;
    private int amount;
    private int appliedAmount;

    public DamageContext(EntityId source, EntityId target, int amount) {
        this(source, target, amount, DamageKind.NORMAL);
    }

    public DamageContext(EntityId source, EntityId target, int amount, DamageKind kind) {
        this.source = source;
        this.target = target;
        this.amount = amount;
        this.kind = kind;
    }

    public EntityId source() {
        return source;
    }

    public EntityId target() {
        return target;
    }

    public DamageKind kind() {
        return kind;
    }

    public int amount() {
        return amount;
    }

    public int appliedAmount() {
        return appliedAmount;
    }

    public void increaseAmount(int delta) {
        amount += delta;
    }

    void setAppliedAmount(int appliedAmount) {
        this.appliedAmount = appliedAmount;
    }
}
