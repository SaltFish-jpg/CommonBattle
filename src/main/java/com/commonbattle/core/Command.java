package com.commonbattle.core;

import java.util.List;

/**
 * External input or AI decision.
 * A command describes intent and expands into effects after the active {@link RuleSet} validates it.
 */
public interface Command {
    List<Effect> effects(BattleContext context);
}
