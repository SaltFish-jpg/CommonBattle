package com.commonbattle.battle.rule;



import com.commonbattle.battle.buff.StatusEffect;
import com.commonbattle.battle.command.AttackCommand;
import com.commonbattle.battle.command.CastSkillCommand;
import com.commonbattle.battle.command.Command;
import com.commonbattle.battle.component.BuffComponent;
import com.commonbattle.battle.component.HealthComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.state.Entity;
/**
 * 可复用的最小单位战斗规则集。
 * 更具体的玩法可以继承它，并补充模式自己的校验。
 */
public class BasicRuleSet implements RuleSet {
    @Override
    public void validate(BattleContext context, Command command) {
        if (command instanceof AttackCommand attack) {
            Entity attacker = context.state().requireEntity(attack.attacker());
            Entity target = context.state().requireEntity(attack.target());
            ensureCanAct(attacker);
            if (!attacker.require(HealthComponent.class).alive()) {
                throw new IllegalStateException("Dead attacker cannot attack");
            }
            if (!target.require(HealthComponent.class).alive()) {
                throw new IllegalStateException("Dead target cannot be attacked");
            }
        }
        if (command instanceof CastSkillCommand cast) {
            Entity caster = context.state().requireEntity(cast.caster());
            ensureCanAct(caster);
            if (!caster.require(HealthComponent.class).alive()) {
                throw new IllegalStateException("Dead caster cannot cast skill");
            }
        }
    }

    protected void ensureCanAct(Entity actor) {
        actor.find(BuffComponent.class).ifPresent(buffs -> {
            if (buffs.hasStatus(StatusEffect.STUNNED)) {
                throw new IllegalStateException("Stunned unit cannot act");
            }
            if (buffs.hasStatus(StatusEffect.FROZEN)) {
                throw new IllegalStateException("Frozen unit cannot act");
            }
        });
    }
}
