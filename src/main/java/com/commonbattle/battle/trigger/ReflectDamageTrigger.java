package com.commonbattle.battle.trigger;



import com.commonbattle.battle.component.ReflectDamageComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DamageContext;
import com.commonbattle.battle.effect.DamageKind;
import com.commonbattle.battle.effect.DealDamageEffect;
/**
 * 伤害后结算反伤的触发器。
 */
public final class ReflectDamageTrigger implements Trigger {
    @Override
    public void execute(BattleContext context) {
        DamageContext damage = context.require(DamageContext.class);
        if (damage.kind() == DamageKind.REFLECT || damage.appliedAmount() <= 0 || damage.source().equals(damage.target())) {
            return;
        }

        context.state().requireEntity(damage.target()).find(ReflectDamageComponent.class).ifPresent(reflect -> {
            int amount = reflect.flatAmount() + (int) Math.floor(damage.appliedAmount() * reflect.ratio());
            if (amount <= 0) {
                return;
            }
            // 反伤标记为 REFLECT，避免双方都有反伤时形成无限递归。
            new DealDamageEffect(damage.target(), damage.source(), amount, DamageKind.REFLECT).apply(context);
        });
    }
}
