package com.commonbattle.battle.effect;



import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.event.EntitySummonedEvent;
import com.commonbattle.battle.event.EventBus;
import com.commonbattle.battle.state.Entity;
import com.commonbattle.battle.state.EntityId;
import java.util.function.Consumer;

/**
 * 创建召唤物实体的效果。
 * 调用方通过初始化回调为召唤物挂载生命、阵营、属性、站位等组件。
 */
public record SummonEffect(EntityId summoner, String type, Consumer<Entity> initializer) implements Effect {
    @Override
    public void apply(BattleContext context) {
        Entity summoned = context.state().createEntity(type);
        initializer.accept(summoned);
        context.log().add("effect.summon", "%s summoned %s as %s"
                .formatted(summoner.value(), summoned.id().value(), type));
        context.eventBus().publish(new EntitySummonedEvent(summoner, summoned.id(), type));
    }
}
