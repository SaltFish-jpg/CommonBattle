package com.commonbattle.core;

/**
 * Minimal reusable rule set for unit attacks.
 * More specialized games can extend this class and add mode-specific validation.
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
