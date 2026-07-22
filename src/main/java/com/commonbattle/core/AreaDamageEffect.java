package com.commonbattle.core;

/**
 * 对结算时选中的每个目标造成相同伤害。
 */
public record AreaDamageEffect(EntityId source, TargetSelector selector, int amount) implements Effect {
    @Override
    public void apply(BattleContext context) {
        for (EntityId target : selector.select(context)) {
            new DealDamageEffect(source, target, amount).apply(context);
        }
        context.log().add("effect.area_damage", "%s dealt %d area damage".formatted(source.value(), amount));
    }
}
