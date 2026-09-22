package com.commonbattle.battle.effect;



import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.event.UnitDamagedEvent;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
import com.commonbattle.battle.trigger.TriggerSystem;
import com.commonbattle.battle.trigger.TriggerTiming;
/**
 * 对目标造成伤害，并开放伤害前后触发窗口及伤害事件。
 */
public record DealDamageEffect(EntityId source, EntityId target, int amount, DamageKind kind) implements Effect {
    public DealDamageEffect(EntityId source, EntityId target, int amount) {
        this(source, target, amount, DamageKind.NORMAL);
    }

    @Override
    public void apply(BattleContext context) {
        DamageContext damage = new DamageContext(source, target, amount, kind);
        context.withScoped(DamageContext.class, damage, () -> {
            context.triggerSystem().fire(TriggerTiming.BEFORE_DAMAGE, context);
            Entity targetEntity = context.state().requireEntity(damage.target());
            HealthComponent health = targetEntity.require(HealthComponent.class);
            int applied = health.damage(damage.amount());
            damage.setAppliedAmount(applied);
            context.log().add("effect.damage", "%s dealt %d damage to %s"
                    .formatted(source.value(), applied, target.value()));
            context.eventBus().publish(new UnitDamagedEvent(source, target, applied, health.current()));
            context.triggerSystem().fire(TriggerTiming.AFTER_DAMAGE, context);
        });
    }
}
