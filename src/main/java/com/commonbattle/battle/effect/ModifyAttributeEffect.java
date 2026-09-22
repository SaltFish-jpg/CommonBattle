package com.commonbattle.battle.effect;



import com.commonbattle.battle.buff.Buff;
import com.commonbattle.battle.context.BattleContext;
import com.commonbattle.battle.state.EntityId;
/**
 * 将属性修正建模为 Buff 的便捷效果。
 */
public record ModifyAttributeEffect(EntityId target, String buffId, String attribute, int delta, int remainingTurns) implements Effect {
    @Override
    public void apply(BattleContext context) {
        new AddBuffEffect(target, Buff.attribute(buffId, attribute, delta, remainingTurns)).apply(context);
    }
}
