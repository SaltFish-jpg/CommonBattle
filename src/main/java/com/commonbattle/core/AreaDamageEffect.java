package com.commonbattle.core;

/**
 * Deals the same damage to every target selected at resolution time.
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
