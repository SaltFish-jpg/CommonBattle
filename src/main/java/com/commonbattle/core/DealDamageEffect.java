package com.commonbattle.core;

/**
 * Applies damage to a target with before/after damage trigger windows and a damage event.
 */
public record DealDamageEffect(EntityId source, EntityId target, int amount) implements Effect {
    @Override
    public void apply(BattleContext context) {
        DamageContext damage = new DamageContext(source, target, amount);
        context.withScoped(DamageContext.class, damage, () -> {
            context.triggerSystem().fire(TriggerTiming.BEFORE_DAMAGE, context);
            Entity targetEntity = context.state().requireEntity(damage.target());
            HealthComponent health = targetEntity.require(HealthComponent.class);
            int applied = health.damage(damage.amount());
            context.log().add("effect.damage", "%s dealt %d damage to %s"
                    .formatted(source.value(), applied, target.value()));
            context.eventBus().publish(new UnitDamagedEvent(source, target, applied, health.current()));
            context.triggerSystem().fire(TriggerTiming.AFTER_DAMAGE, context);
        });
    }
}
