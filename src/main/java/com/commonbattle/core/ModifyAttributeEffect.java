package com.commonbattle.core;

/**
 * Convenience effect that models an attribute modifier as a buff.
 */
public record ModifyAttributeEffect(EntityId target, String buffId, String attribute, int delta, int remainingTurns) implements Effect {
    @Override
    public void apply(BattleContext context) {
        new AddBuffEffect(target, Buff.attribute(buffId, attribute, delta, remainingTurns)).apply(context);
    }
}
