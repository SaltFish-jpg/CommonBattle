package com.commonbattle.core;

/**
 * 将属性修正建模为 Buff 的便捷效果。
 */
public record ModifyAttributeEffect(EntityId target, String buffId, String attribute, int delta, int remainingTurns) implements Effect {
    @Override
    public void apply(BattleContext context) {
        new AddBuffEffect(target, Buff.attribute(buffId, attribute, delta, remainingTurns)).apply(context);
    }
}
