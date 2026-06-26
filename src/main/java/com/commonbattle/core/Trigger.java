package com.commonbattle.core;

/**
 * Hook for ordered settlement moments such as before damage or turn start.
 * Buffs, passives, equipment, and aura logic usually live behind triggers.
 */
@FunctionalInterface
public interface Trigger {
    void execute(BattleContext context);
}
