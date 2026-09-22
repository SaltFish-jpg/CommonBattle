package com.commonbattle.battle.effect;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.component.BuffComponent;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.event.BuffAddedEvent;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
/**
 * 给实体添加 Buff；目标还没有 Buff 组件时会自动创建。
 */
public record AddBuffEffect(EntityId target, Buff buff) implements Effect {
    @Override
    public void apply(BattleContext context) {
        Entity entity = context.state().requireEntity(target);
        BuffComponent buffs = entity.find(BuffComponent.class).orElseGet(() -> {
            BuffComponent created = new BuffComponent();
            entity.add(created);
            return created;
        });
        buffs.add(buff);
        context.log().add("buff.added", "%s gained buff %s".formatted(target.value(), buff.id()));
        context.eventBus().publish(new BuffAddedEvent(target, buff));
    }
}
