package com.commonbattle.battle.effect;



import com.commonbattle.battle.command.CastSkillCommand;
import com.commonbattle.battle.component.RageEnergyComponent;
import com.commonbattle.battle.component.RageSkillComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.event.RageChangedEvent;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
/**
 * 增加实体怒气，并在满怒时消耗怒气提交对应技能。
 */
public record AddRageEffect(EntityId entity, int amount, String reason) implements Effect {
    @Override
    public void apply(BattleContext context) {
        Entity target = context.state().requireEntity(entity);
        RageEnergyComponent rage = target.require(RageEnergyComponent.class);
        int applied = rage.add(amount);
        context.log().add("effect.rage_gain", "%s gained %d rage by %s"
                .formatted(entity.value(), applied, reason));
        context.eventBus().publish(new RageChangedEvent(entity, applied, rage.current(), rage.max(), reason));
        if (applied > 0 && rage.full()) {
            castRageSkill(context, target, rage);
        }
    }

    private void castRageSkill(BattleContext context, Entity entity, RageEnergyComponent rage) {
        entity.find(RageSkillComponent.class).ifPresent(skill -> {
            // 满怒后先清空怒气再排队技能，避免技能造成伤害时重入重复释放。
            if (rage.consumeFull()) {
                context.log().add("effect.rage_skill_ready", "%s consumed full rage to cast %s"
                        .formatted(entity.id().value(), skill.skillId()));
                context.eventBus().publish(new RageChangedEvent(entity.id(), -rage.max(), rage.current(), rage.max(), "rage_skill"));
                context.submit(new CastSkillCommand(entity.id(), skill.skillId(), skill.effects()));
            }
        });
    }
}
