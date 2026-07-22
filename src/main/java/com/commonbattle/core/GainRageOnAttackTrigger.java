package com.commonbattle.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 普通攻击造成伤害后回怒的触发器。
 * 攻击者按攻击回怒，被击者按受击回怒；技能伤害不会触发，避免怒气技能自循环。
 */
public final class GainRageOnAttackTrigger implements Trigger {
    @Override
    public void execute(BattleContext context) {
        if (context.find(Command.class).filter(AttackCommand.class::isInstance).isEmpty()) {
            return;
        }

        DamageContext damage = context.require(DamageContext.class);
        if (damage.kind() != DamageKind.NORMAL) {
            return;
        }
        List<Effect> gains = new ArrayList<>();
        addAttackGain(context, damage, gains);
        addDamagedGain(context, damage, gains);

        // 回怒本身也走效果解析，保持日志、事件和后续扩展都在统一流水线内。
        for (Effect gain : gains) {
            gain.apply(context);
        }
    }

    private void addAttackGain(BattleContext context, DamageContext damage, List<Effect> gains) {
        context.state().requireEntity(damage.source())
                .find(RageEnergyComponent.class)
                .filter(rage -> rage.gainOnAttack() > 0)
                .ifPresent(rage -> gains.add(new AddRageEffect(damage.source(), rage.gainOnAttack(), "attack")));
    }

    private void addDamagedGain(BattleContext context, DamageContext damage, List<Effect> gains) {
        context.state().requireEntity(damage.target())
                .find(RageEnergyComponent.class)
                .filter(rage -> rage.gainOnDamaged() > 0)
                .ifPresent(rage -> gains.add(new AddRageEffect(damage.target(), rage.gainOnDamaged(), "damaged")));
    }
}
