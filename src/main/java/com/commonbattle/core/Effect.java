package com.commonbattle.core;

/**
 * Atomic settlement operation.
 * Skills, cards, buffs, and rules compose effects instead of hardcoding large gameplay flows.
 */
public interface Effect {
    void apply(BattleContext context);
}
