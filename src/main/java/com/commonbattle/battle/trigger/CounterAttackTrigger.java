package com.commonbattle.battle.trigger;



import com.commonbattle.battle.buff.AttributeValueResolver;
import com.commonbattle.battle.command.AttackCommand;
import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.component.CounterAttackComponent;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.effect.DamageContext;
import com.commonbattle.battle.effect.DamageKind;
import com.commonbattle.battle.effect.DealDamageEffect;
import com.commonbattle.battle.state.Entity;
/**
 * 普通攻击命中后立即反击的触发器。
 */
public final class CounterAttackTrigger implements Trigger {
    @Override
    public void execute(BattleContext context) {
        DamageContext damage = context.require(DamageContext.class);
        if (damage.kind() != DamageKind.NORMAL || damage.appliedAmount() <= 0) {
            return;
        }
        if (context.find(Command.class).filter(AttackCommand.class::isInstance).isEmpty()) {
            return;
        }

        Entity defender = context.state().requireEntity(damage.target());
        Entity attacker = context.state().requireEntity(damage.source());
        if (!defender.require(HealthComponent.class).alive() || !attacker.require(HealthComponent.class).alive()) {
            return;
        }

        defender.find(CounterAttackComponent.class).ifPresent(counter -> {
            int attack = AttributeValueResolver.resolve(defender, "attack");
            int amount = (int) Math.floor(attack * counter.damageRatio());
            if (amount <= 0) {
                return;
            }
            // 反击直接结算为 COUNTER 伤害，不提交 AttackCommand，避免再次触发反击链。
            new DealDamageEffect(damage.target(), damage.source(), amount, DamageKind.COUNTER).apply(context);
        });
    }
}
