package com.commonbattle.core;

/**
 * Pluggable gameplay rules.
 * Implementations decide whether a command is legal in the current battle mode, phase, resource state, or timing.
 */
public interface RuleSet {
    void validate(BattleContext context, Command command);
}
