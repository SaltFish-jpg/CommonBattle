package com.commonbattle.core;

import java.util.List;

/**
 * Executes effects in order.
 * A custom resolver can later add batching, rollback, deterministic replay checks, or priority queues.
 */
public final class EffectResolver {
    public void resolve(BattleContext context, List<Effect> effects) {
        for (Effect effect : effects) {
            effect.apply(context);
        }
    }
}
